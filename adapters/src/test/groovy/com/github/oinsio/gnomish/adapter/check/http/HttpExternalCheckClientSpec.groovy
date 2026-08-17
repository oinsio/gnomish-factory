package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * FR9, FR10 of add-plugin-architecture: the http check's verdict is declarative. A one-shot probe
 * passes on 2xx with no polling; a {@code pass-when} narrows that by a jsonPath and/or regex
 * extraction compared with {@code equals}; a matching {@code pending-when} keeps the engine polling.
 *
 * FR11, NFR-S1: authorization is a credential resolved by name at request time and set as a header —
 * the manifest carries the name, the request carries the value, and an unresolvable name is a
 * fail-closed CannotVerify naming the secret.
 */
class HttpExternalCheckClientSpec extends Specification implements HttpCheckFixture {

    private PollStatus poll(Map<String, Object> params, HttpCheckFixture.ScriptedExchange exchange,
            Map<String, String> secrets = [:]) {
        new HttpExternalCheckClient(exchange, providing(secrets)).poll(check(params), null)
    }

    // FR10: a check declaring only the default pass_when passes on 2xx after one request — the
    //     one-shot probe is a degenerate poll, not a second code path.
    def "a one-shot 2xx probe passes without polling"() {
        given:
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'anything at all')

        when:
        def status = poll([url: URL], exchange)

        then: 'it passed, pointing at the endpoint that decided it'
        status instanceof PollStatus.Pass
        status.runUrl() == URL

        and: 'exactly one request was sent, with the default method'
        exchange.lastRequest.method() == 'GET'
        exchange.lastRequest.uri().toString() == URL
    }

    // FR10: a non-2xx with no narrowing condition fails, carrying the response as findings.
    def "a non-2xx response fails with the response captured as findings"() {
        when:
        def status = poll([url: URL], new HttpCheckFixture.ScriptedExchange(500, 'boom'))

        then:
        status instanceof PollStatus.Fail
        status.findings().size() == 1
        status.findings()[0].message().contains('quality-gate')
        status.findings()[0].message().contains('HTTP 500')
        status.findings()[0].location() == URL
        status.findings()[0].details() == 'boom'
    }

    // FR10, D4: jsonPath narrows a 2xx — JSON status documents are one of the two body shapes CI
    //     and quality APIs split between.
    def "pass_when narrows a 2xx on an extracted jsonPath value"() {
        given:
        def params = [url: URL, ('pass-when'): [('json-path'): '$.projectStatus.status', equals: 'OK']]

        when:
        def status = poll(params, new HttpCheckFixture.ScriptedExchange(200, body))

        then:
        status.class == expected

        where:
        body || expected
        '{"projectStatus":{"status":"OK"}}' || PollStatus.Pass
        '{"projectStatus":{"status":"ERROR"}}' || PollStatus.Fail
        '{"projectStatus":{}}' || PollStatus.Fail
        'not json at all' || PollStatus.Fail
    }

    // FR10, D4: regex covers the other half — plain-text and heterogeneous bodies.
    def "pass_when narrows a 2xx on an extracted regex group"() {
        given:
        def params = [url: URL, ('pass-when'): [regex: 'status=(\\w+)', equals: 'green']]

        when:
        def status = poll(params, new HttpCheckFixture.ScriptedExchange(200, body))

        then:
        status.class == expected

        where:
        body || expected
        'build status=green done' || PollStatus.Pass
        'build status=red done' || PollStatus.Fail
        'nothing to extract here' || PollStatus.Fail
    }

    // D4: both extractors compose — jsonPath selects the node, the regex extracts from its text.
    def "pass_when composes jsonPath then regex"() {
        given:
        def params = [
            url: URL,
            ('pass-when'): [('json-path'): 'analysis.summary', regex: 'gate:(\\w+)', equals: 'passed']
        ]

        when:
        def status = poll(params, new HttpCheckFixture.ScriptedExchange(200, body))

        then:
        status.class == expected

        where:
        body || expected
        '{"analysis":{"summary":"gate:passed 12 issues"}}' || PollStatus.Pass
        '{"analysis":{"summary":"gate:failed 12 issues"}}' || PollStatus.Fail
    }

    // FR10: an extraction that matches pass_when is still a failure when the status is not 2xx —
    //     pass_when NARROWS the 2xx default rather than replacing it.
    def "pass_when cannot rescue a non-2xx status"() {
        given:
        def params = [url: URL, ('pass-when'): [('json-path'): 'status', equals: 'OK']]

        expect:
        poll(params, new HttpCheckFixture.ScriptedExchange(503, '{"status":"OK"}')) instanceof PollStatus.Fail
    }

    // FR10: pending_when marks a response non-terminal, which is what makes the engine poll again
    //     at the check's interval and classify a timeout by the check's own timeoutClass.
    def "pending_when keeps the check running until the response is terminal"() {
        given:
        def params = [
            url: URL,
            ('pass-when'): [('json-path'): 'status', equals: 'SUCCESS'],
            ('pending-when'): [('json-path'): 'status', equals: 'IN_PROGRESS']
        ]

        when:
        def status = poll(params, new HttpCheckFixture.ScriptedExchange(200, body))

        then:
        status.class == expected

        where:
        body || expected
        '{"status":"IN_PROGRESS"}' || PollStatus.Running
        '{"status":"SUCCESS"}' || PollStatus.Pass
        '{"status":"FAILED"}' || PollStatus.Fail
    }

    // FR10: a service reporting "still working" with a non-2xx status is still pending, not a
    //     failure — the pending question is asked before the status is judged.
    def "pending_when is honoured regardless of the status code"() {
        given:
        def params = [url: URL, ('pending-when'): [('json-path'): 'state', equals: 'QUEUED']]

        expect:
        poll(params, new HttpCheckFixture.ScriptedExchange(202, '{"state":"QUEUED"}')) instanceof PollStatus.Running
    }

    // FR11, NFR-S1: the secret is resolved by name and set on the request; the manifest never holds it.
    def "the named credential is resolved and set as a header at request time"() {
        given:
        def params = [url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']]
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')

        when:
        def status = poll(params, exchange, [GNOMISH_SONAR_TOKEN: 's3cret'])

        then:
        status instanceof PollStatus.Pass
        exchange.lastRequest.headers().firstValue('Authorization').get() == 'Bearer s3cret'
    }

    // FR11: the header and scheme are declarable, for services that do not speak bearer tokens.
    def "the credential header and scheme are declarable"() {
        given:
        def params = [url: URL, auth: [credential: 'TOKEN', header: 'X-Api-Key', scheme: '']]
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')

        when:
        poll(params, exchange, [TOKEN: 'raw-value'])

        then:
        exchange.lastRequest.headers().firstValue('X-Api-Key').get() == 'raw-value'
    }

    // FR11, NFR-S1: an unresolvable credential is fail-closed — no request is sent, and the reason
    //     names the secret so an operator can fix it from the escalation report.
    def "an unresolvable credential is a CannotVerify naming the secret, sending nothing"() {
        given:
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')

        when:
        def status = poll([url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']], exchange, [:])

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('GNOMISH_SONAR_TOKEN')
        exchange.lastRequest == null
    }

    // FR10: an unreachable endpoint says nothing about the artifact — infrastructure, not quality,
    //     so no stage attempt is burned.
    def "an exchange that cannot complete is a CannotVerify naming the endpoint"() {
        when:
        def status = poll([url: URL], new HttpCheckFixture.ScriptedExchange(new IOException('connect timed out')))

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains(URL)
        status.details().contains('connect timed out')
    }

    // FR10: the same classification for an interrupted wait, with the interrupt flag preserved.
    def "an interrupted exchange is a CannotVerify and restores the interrupt flag"() {
        when:
        def status = poll([url: URL], new HttpCheckFixture.ScriptedExchange(new InterruptedException('stopped')))

        then:
        status instanceof PollStatus.CannotVerify
        Thread.interrupted()
    }

    // NFR-O1: a huge body would swamp the tracker report, so the finding carries an excerpt.
    def "a failing response's body is excerpted into the findings"() {
        given:
        def body = 'x' * (HttpExternalCheckClient.BODY_EXCERPT_LIMIT + 100)

        when:
        def status = poll([url: URL], new HttpCheckFixture.ScriptedExchange(500, body))

        then:
        status.findings()[0].details().length() == HttpExternalCheckClient.BODY_EXCERPT_LIMIT + 1
        status.findings()[0].details().endsWith('…')
    }

    // NFR-O1: the finding says what was expected in the manifest's own vocabulary, so an author
    //     reads the unmet condition rather than inferring it from a status code.
    def "the finding renders the unmet condition as it was declared"() {
        when:
        def status = poll([url: URL, ('pass-when'): passWhen], new HttpCheckFixture.ScriptedExchange(500, 'nope'))

        then:
        status.findings()[0].message().contains(expected)

        where:
        passWhen || expected
        [:] || 'expected HTTP 2xx'
        [('json-path'): '$.status', equals: 'OK'] || "json-path '\$.status' equals 'OK'"
        [regex: 'st=(\\w+)', equals: 'OK'] || "regex 'st=(\\w+)' equals 'OK'"
        [('json-path'): '$.s', regex: 'x(\\w)', equals: 'OK'] || "json-path '\$.s' then regex 'x(\\w)' equals 'OK'"
    }

    // NFR-O1: a body exactly at the limit is carried whole — only a longer one is cut.
    def "a body at the excerpt limit is carried whole"() {
        given:
        def body = 'x' * HttpExternalCheckClient.BODY_EXCERPT_LIMIT

        when:
        def status = poll([url: URL], new HttpCheckFixture.ScriptedExchange(500, body))

        then:
        status.findings()[0].details() == body
    }

    // FR10: the declared method and headers travel onto the request verbatim.
    def "the declared method and non-secret headers are sent"() {
        given:
        def params = [url: URL, method: 'POST', headers: [Accept: 'application/json']]
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')

        when:
        poll(params, exchange)

        then:
        exchange.lastRequest.method() == 'POST'
        exchange.lastRequest.headers().firstValue('Accept').get() == 'application/json'
    }

    // NFR-S2: a refused target is reported as the egress rule that refused it — an infrastructure
    //     failure that burns no stage attempt, since a blocked target says nothing about the artifact.
    def "a target the egress allowlist refuses is a CannotVerify naming the reason"() {
        given:
        def refusal = new EgressRefusal(EgressRefusal.Reason.ADDRESS_CLASS, URL, 'resolves to 169.254.169.254')
        def exchange = new HttpCheckFixture.ScriptedExchange(new EgressRefusedException(refusal))

        when:
        def status = poll([url: URL], exchange)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('169.254.169.254')
        status.details() == 'address class'
    }

    // NFR-S2: the whitelisted run variables reach the composed request, so a check addresses exactly
    //     this run's result.
    def "whitelisted run variables are interpolated into the url and headers"() {
        given:
        def runContext = { name ->
            Optional.ofNullable(['task.id': 'PROJ-42', 'task.branch': 'gnomish/PROJ-42',
                'stage.name': 'implement'][name])
        } as com.github.oinsio.gnomish.app.CheckRunContext
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')
        def params = [url: 'https://ci.example.invalid/api?branch=${task.branch}&rev=${attempt.commit}',
            headers: ['X-Stage': '${stage.name}']]

        when:
        def status = new HttpExternalCheckClient(exchange, providing([:]), runContext)
        .poll(check(params), new com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace(
                commitRef('c0ffee')))

        then:
        exchange.lastRequest.uri().toString() ==
                'https://ci.example.invalid/api?branch=gnomish/PROJ-42&rev=c0ffee'
        exchange.lastRequest.headers().firstValue('X-Stage').get() == 'implement'

        and: 'the verdict points at the resolved target, not at the template'
        status.runUrl() == 'https://ci.example.invalid/api?branch=gnomish/PROJ-42&rev=c0ffee'
    }

    // NFR-S2: fail closed — a run that cannot supply a value never guesses one.
    def "a reference this run cannot supply is a CannotVerify naming the variable"() {
        given:
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')

        when:
        def status = poll([url: 'https://ci.example.invalid/${attempt.commit}'], exchange)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }

    // NFR-S2: a round not yet closed by a snapshot carries no attempt commit either — same fail-closed
    //     answer as a workspace that never had one, never an empty substitution.
    def "a workspace whose round recorded no attempt commit fails the check closed"() {
        given:
        def exchange = new HttpCheckFixture.ScriptedExchange(200, 'ok')
        def workspace = new com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace(
                new com.github.oinsio.gnomish.app.port.git.AttemptCommitRef())

        when:
        def status = new HttpExternalCheckClient(exchange, providing([:]))
        .poll(check([url: 'https://ci.example.invalid/${attempt.commit}']), workspace)

        then:
        status instanceof PollStatus.CannotVerify
        status.reason().contains('attempt.commit')
        exchange.lastRequest == null
    }

    private static com.github.oinsio.gnomish.app.port.git.AttemptCommitRef commitRef(String sha) {
        def ref = new com.github.oinsio.gnomish.app.port.git.AttemptCommitRef()
        ref.record(sha)
        ref
    }
}
