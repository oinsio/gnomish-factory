package com.github.oinsio.gnomish.adapter.tracker.github

import java.time.Instant
import spock.lang.Specification

/**
 * GithubMarker (FR7 of add-tracker-port, design D9, NFR-O1 of add-tracker-port):
 * encodes/decodes the GitHub adapter's structural comment shape — a leading
 * hidden HTML comment carrying one-line JSON (kind, instance, at, version) followed
 * by human-readable text. The JSON line carries only coordination metadata,
 * never the human message itself; humans see the plain-text line because
 * GitHub renders HTML comments invisibly, while a fresh adapter instance
 * parses kind/instance/at/version back out of the comment body.
 *
 * Implements FR7 of add-tracker-port.
 */
class GithubMarkerSpec extends Specification {

    def "render wraps the structural JSON in an HTML comment, followed by the human text on its own line"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')

        when:
        def body = GithubMarker.render(GithubMarkerKind.CLAIM, 'gnomish-factory-x7k2q1', at,
                '🤖 gnomish: claimed by gnomish-factory-x7k2q1')

        then:
        body == '<!-- gnomish {"kind":"claim","instance":"gnomish-factory-x7k2q1","at":"2026-07-20T12:00:00Z","version":1} -->\n' +
                '🤖 gnomish: claimed by gnomish-factory-x7k2q1'
    }

    def "render output starts with the HTML comment delimiter and the human text is plain outside it"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')

        when:
        def body = GithubMarker.render(GithubMarkerKind.NOTE, 'gnomish-factory-abc123', at, 'work stopped')

        then:
        body.startsWith('<!-- gnomish ')
        def lines = body.split('\n', 2)
        lines[0].startsWith('<!--') && lines[0].endsWith('-->')
        lines[1] == 'work stopped'
        !lines[1].contains('<!--')
    }

    def "round-trip: render then parse recovers kind, instance, at, v, and human text exactly (#kind)"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')
        def humanText = '🤖 gnomish: ' + kind.name().toLowerCase()

        when:
        def body = GithubMarker.render(kind, 'gnomish-factory-x7k2q1', at, humanText)
        def parsed = GithubMarker.parse(body)

        then:
        parsed.isPresent()
        parsed.get().kind() == kind
        parsed.get().instance() == 'gnomish-factory-x7k2q1'
        parsed.get().at() == at
        parsed.get().version() == 1
        parsed.get().humanText() == humanText

        where:
        kind << GithubMarkerKind.values()
    }

    def "parse recognizes multi-line human text after the structural comment"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')
        def humanText = 'line one\nline two\nline three'

        when:
        def body = GithubMarker.render(GithubMarkerKind.REPORT, 'gnomish-factory-x7k2q1', at, humanText)
        def parsed = GithubMarker.parse(body)

        then:
        parsed.get().humanText() == humanText
    }

    def "parse returns empty for a comment with no gnomish structural marker (an operator's own comment)"() {
        expect:
        GithubMarker.parse('just a human reply, no marker here').isEmpty()
    }

    def "parse returns empty for blank input"() {
        expect:
        GithubMarker.parse('').isEmpty()
    }

    def "parse returns empty for an unrelated HTML comment that is not the gnomish marker"() {
        expect:
        GithubMarker.parse('<!-- some other tool\'s marker -->\nhello').isEmpty()
    }

    def "parse returns empty for a gnomish-prefixed comment with malformed JSON"() {
        expect:
        GithubMarker.parse('<!-- gnomish {not valid json} -->\nhello').isEmpty()
    }

    def "the format version is a fixed code constant, not a render parameter"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')

        when:
        def body = GithubMarker.render(GithubMarkerKind.ACK, 'gnomish-factory-x7k2q1', at, 'acting on decision: x')
        def parsed = GithubMarker.parse(body)

        then:
        body.contains('"version":1')
        parsed.get().version() == 1
    }
}
