package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.app.CheckRunContext
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * FR10, FR11, NFR-S1, NFR-S2 of add-plugin-architecture: how the request is composed. The method
 * and headers travel from the manifest verbatim, authorization is a credential resolved by name at
 * request time, and the whitelisted run variables are interpolated so a check addresses exactly
 * this run's result. The fail-closed halves of these rules live in
 * {@link HttpExternalCheckClientCannotVerifySpec}.
 */
class HttpExternalCheckClientRequestSpec extends Specification implements HttpCheckFixture {

    // FR10: the declared method and headers travel onto the request verbatim.
    def "the declared method and non-secret headers are sent"() {
        given:
        def params = [url: URL, method: 'POST', headers: [Accept: 'application/json']]
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        poll(params, exchange)

        then:
        exchange.lastRequest.method() == 'POST'
        exchange.lastRequest.headers().firstValue('Accept').get() == 'application/json'
    }

    // FR11, NFR-S1: the secret is resolved by name and set on the request; the manifest never holds it.
    def "the named credential is resolved and set as a header at request time"() {
        given:
        def params = [url: URL, auth: [credential: 'GNOMISH_SONAR_TOKEN']]
        def exchange = new ScriptedExchange(200, 'ok')

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
        def exchange = new ScriptedExchange(200, 'ok')

        when:
        poll(params, exchange, [TOKEN: 'raw-value'])

        then:
        exchange.lastRequest.headers().firstValue('X-Api-Key').get() == 'raw-value'
    }

    // NFR-S2: the whitelisted run variables reach the composed request, so a check addresses exactly
    // this run's result.
    def "whitelisted run variables are interpolated into the url and headers"() {
        given:
        def runContext = { name ->
            Optional.ofNullable(['task.id': 'PROJ-42', 'task.branch': 'gnomish/PROJ-42',
                'stage.name': 'implement'][name])
        } as CheckRunContext
        def exchange = new ScriptedExchange(200, 'ok')
        def params = [url: 'https://ci.example.invalid/api?branch=${task.branch}&rev=${attempt.commit}',
            headers: ['X-Stage': '${stage.name}']]

        when:
        def status = new HttpExternalCheckClient(exchange, providing([:]), runContext)
        .poll(check(params), new RecordedAttemptCommitWorkspace(commitRef('c0ffee')))

        then:
        exchange.lastRequest.uri().toString() ==
                'https://ci.example.invalid/api?branch=gnomish/PROJ-42&rev=c0ffee'
        exchange.lastRequest.headers().firstValue('X-Stage').get() == 'implement'

        and: 'the verdict points at the resolved target, not at the template'
        status.runUrl() == 'https://ci.example.invalid/api?branch=gnomish/PROJ-42&rev=c0ffee'
    }

    private static AttemptCommitRef commitRef(String sha) {
        def ref = new AttemptCommitRef()
        ref.record(sha)
        ref
    }
}
