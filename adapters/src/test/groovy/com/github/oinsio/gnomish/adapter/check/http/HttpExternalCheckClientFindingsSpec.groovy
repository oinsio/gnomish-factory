package com.github.oinsio.gnomish.adapter.check.http

import spock.lang.Specification

/**
 * NFR-O1 of add-plugin-architecture: what a failing check reports back. The body is excerpted so a
 * huge response cannot swamp the tracker report, and the unmet condition is rendered in the
 * manifest's own vocabulary so an author reads what was declared rather than inferring it.
 */
class HttpExternalCheckClientFindingsSpec extends Specification implements HttpCheckFixture {

    // NFR-O1: a huge body would swamp the tracker report, so the finding carries an excerpt.
    def "a failing response's body is excerpted into the findings"() {
        given:
        def body = 'x' * (HttpExternalCheckClient.BODY_EXCERPT_LIMIT + 100)

        when:
        def status = poll([url: URL], new ScriptedExchange(500, body))

        then:
        status.findings()[0].details().length() == HttpExternalCheckClient.BODY_EXCERPT_LIMIT + 1
        status.findings()[0].details().endsWith('…')
    }

    // NFR-O1: a body exactly at the limit is carried whole — only a longer one is cut.
    def "a body at the excerpt limit is carried whole"() {
        given:
        def body = 'x' * HttpExternalCheckClient.BODY_EXCERPT_LIMIT

        when:
        def status = poll([url: URL], new ScriptedExchange(500, body))

        then:
        status.findings()[0].details() == body
    }

    // NFR-O1: the finding says what was expected in the manifest's own vocabulary, so an author
    //     reads the unmet condition rather than inferring it from a status code.
    def "the finding renders the unmet condition as it was declared"() {
        when:
        def status = poll([url: URL, ('pass-when'): passWhen], new ScriptedExchange(500, 'nope'))

        then:
        status.findings()[0].message().contains(expected)

        where:
        passWhen || expected
        [:] || 'expected HTTP 2xx'
        [('json-path'): '$.status', equals: 'OK'] || "json-path '\$.status' equals 'OK'"
        [regex: 'st=(\\w+)', equals: 'OK'] || "regex 'st=(\\w+)' equals 'OK'"
        [('json-path'): '$.s', regex: 'x(\\w)', equals: 'OK'] || "json-path '\$.s' then regex 'x(\\w)' equals 'OK'"
    }
}
