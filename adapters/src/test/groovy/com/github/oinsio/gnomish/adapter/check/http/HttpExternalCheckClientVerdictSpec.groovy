package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * FR9, FR10 of add-plugin-architecture: the http check's verdict is declarative. A one-shot probe
 * passes on 2xx with no polling; a {@code pass-when} narrows that by a jsonPath and/or regex
 * extraction compared with {@code equals}; a matching {@code pending-when} keeps the engine polling.
 */
class HttpExternalCheckClientVerdictSpec extends Specification implements HttpCheckFixture {

    // FR10: a check declaring only the default pass_when passes on 2xx after one request — the
    //     one-shot probe is a degenerate poll, not a second code path.
    def "a one-shot 2xx probe passes without polling"() {
        given:
        def exchange = new ScriptedExchange(200, 'anything at all')

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
        def status = poll([url: URL], new ScriptedExchange(500, 'boom'))

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
        def status = poll(params, new ScriptedExchange(200, body))

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
        def status = poll(params, new ScriptedExchange(200, body))

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
        def status = poll(params, new ScriptedExchange(200, body))

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
        poll(params, new ScriptedExchange(503, '{"status":"OK"}')) instanceof PollStatus.Fail
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
        def status = poll(params, new ScriptedExchange(200, body))

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
        poll(params, new ScriptedExchange(202, '{"state":"QUEUED"}')) instanceof PollStatus.Running
    }
}
