package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import spock.lang.Specification

/**
 * FR9, FR10 of add-plugin-architecture: the http check's verdict is declarative. A one-shot probe
 * passes on 2xx with no polling; a {@code pass-when} narrows that by a jsonPath and/or regex
 * extraction compared with {@code equals}; a matching {@code pending-when} keeps the engine polling.
 * FR6 of migrate-verify-to-executor-contract: driven through the {@code http} built-in executor, a
 * pass is {@code completed} with no result, a failure {@code completed} with an {@code error}
 * result, and a pending response {@code pending} — the engine derives the verdict from the levels.
 */
class HttpExecutorVerdictSpec extends Specification implements HttpCheckFixture {

    // FR10: a check declaring only the default pass_when passes on 2xx after one request — the
    //     one-shot probe is a degenerate poll, not a second code path.
    // Scenario: verification/http-check-provider / One-shot probe passes on 2xx
    def "a one-shot 2xx probe passes without polling"() {
        given:
        def exchange = new ScriptedExchange(200, 'anything at all')

        when:
        def status = run([url: URL], exchange)

        then: 'it completed with no result: a pass'
        outcome(status) == PASS

        and: 'exactly one request was sent, with the default method'
        exchange.lastRequest.method() == 'GET'
        exchange.lastRequest.uri().toString() == URL
    }

    // FR10: a non-2xx with no narrowing condition fails, carrying the response as findings.
    def "a non-2xx response fails with the response captured as findings"() {
        when:
        def status = run([url: URL], new ScriptedExchange(500, 'boom'))

        then: 'one error result naming the check and the status, located at the endpoint, the body as its details'
        status.status() == Status.COMPLETED
        results(status).size() == 1
        results(status)[0].level() == SarifLevel.ERROR
        results(status)[0].message().raw().contains('quality-gate')
        results(status)[0].message().raw().contains('HTTP 500')
        results(status)[0].details().raw() == 'boom'
        !results(status)[0].message().raw().contains('boom')
        results(status)[0].uri().raw() == URL
    }

    // FR10, D4: jsonPath narrows a 2xx — JSON status documents are one of the two body shapes CI
    //     and quality APIs split between.
    // Scenario: verification/http-check-provider / pass-when narrows on an extracted value
    def "pass_when narrows a 2xx on an extracted jsonPath value"() {
        given:
        def params = [url: URL, ('pass-when'): [('json-path'): '$.projectStatus.status', equals: 'OK']]

        when:
        def status = run(params, new ScriptedExchange(200, body))

        then:
        outcome(status) == expected

        where:
        body || expected
        '{"projectStatus":{"status":"OK"}}' || PASS
        '{"projectStatus":{"status":"ERROR"}}' || FAIL
        '{"projectStatus":{}}' || FAIL
        'not json at all' || FAIL
    }

    // D4: the json-path dialect — an optional $ root, dot-separated field names, [n] array indexes
    // Scenario: verification/http-check-provider / json-path addresses fields and array indexes
    def "json-path #path addresses the value at that field"() {
        given:
        def body = '{"projectStatus":{"conditions":[{"status":"OK"},{"status":"ERROR"}]}}'
        def params = [url: URL, ('pass-when'): [('json-path'): path, equals: 'OK']]

        expect:
        outcome(run(params, new ScriptedExchange(200, body))) == expected

        where:
        path || expected
        '$.projectStatus.conditions[0].status' || PASS
        'projectStatus.conditions[0].status' || PASS
        '$.projectStatus.conditions[1].status' || FAIL
        '$.projectStatus.conditions[2].status' || FAIL
    }

    // FR10, D4: regex covers the other half — plain-text and heterogeneous bodies.
    // Scenario: verification/http-check-provider / pass-when narrows on an extracted value
    def "pass_when narrows a 2xx on an extracted regex group"() {
        given:
        def params = [url: URL, ('pass-when'): [regex: 'status=(\\w+)', equals: 'green']]

        when:
        def status = run(params, new ScriptedExchange(200, body))

        then:
        outcome(status) == expected

        where:
        body || expected
        'build status=green done' || PASS
        'build status=red done' || FAIL
        'nothing to extract here' || FAIL
    }

    // D4: both extractors compose — jsonPath selects the node, the regex extracts from its text.
    def "pass_when composes jsonPath then regex"() {
        given:
        def params = [
            url: URL,
            ('pass-when'): [('json-path'): 'analysis.summary', regex: 'gate:(\\w+)', equals: 'passed']
        ]

        when:
        def status = run(params, new ScriptedExchange(200, body))

        then:
        outcome(status) == expected

        where:
        body || expected
        '{"analysis":{"summary":"gate:passed 12 issues"}}' || PASS
        '{"analysis":{"summary":"gate:failed 12 issues"}}' || FAIL
    }

    // FR10: an extraction that matches pass_when is still a failure when the status is not 2xx —
    //     pass_when NARROWS the 2xx default rather than replacing it.
    def "pass_when cannot rescue a non-2xx status"() {
        given:
        def params = [url: URL, ('pass-when'): [('json-path'): 'status', equals: 'OK']]

        expect:
        outcome(run(params, new ScriptedExchange(503, '{"status":"OK"}'))) == FAIL
    }

    // FR10: pending_when marks a response non-terminal, which is what makes the engine poll again
    //     at the check's interval and classify a timeout by the check's own timeoutClass.
    // Scenario: verification/http-check-provider / pending-when polls until terminal
    def "pending_when keeps the check running until the response is terminal"() {
        given:
        def params = [
            url: URL,
            ('pass-when'): [('json-path'): 'status', equals: 'SUCCESS'],
            ('pending-when'): [('json-path'): 'status', equals: 'IN_PROGRESS']
        ]

        when:
        def status = run(params, new ScriptedExchange(200, body))

        then:
        outcome(status) == expected

        where:
        body || expected
        '{"status":"IN_PROGRESS"}' || PENDING
        '{"status":"SUCCESS"}' || PASS
        '{"status":"FAILED"}' || FAIL
    }

    // FR10: a service reporting "still working" with a non-2xx status is still pending, not a
    //     failure — the pending question is asked before the status is judged.
    def "pending_when is honoured regardless of the status code"() {
        given:
        def params = [url: URL, ('pending-when'): [('json-path'): 'state', equals: 'QUEUED']]

        expect:
        outcome(run(params, new ScriptedExchange(202, '{"state":"QUEUED"}'))) == PENDING
    }

    private static final String PASS = 'pass'
    private static final String FAIL = 'fail'
    private static final String PENDING = 'pending'

    /** What the engine would derive from the result: no result passes, an error result fails. */
    private static String outcome(ExecutorResult result) {
        if (result.status() == Status.PENDING) {
            return PENDING
        }
        assert result.status() == Status.COMPLETED
        results(result).isEmpty() ? PASS : FAIL
    }
}
