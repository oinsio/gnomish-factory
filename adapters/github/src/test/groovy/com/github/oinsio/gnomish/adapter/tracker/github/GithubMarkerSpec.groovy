package com.github.oinsio.gnomish.adapter.tracker.github

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
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
        def humanText = '🤖 gnomish: ' + (kind as GithubMarkerKind).name().toLowerCase()

        when:
        def body = GithubMarker.render(kind as GithubMarkerKind, 'gnomish-factory-x7k2q1', at, humanText)
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
        def body = GithubMarker.render(GithubMarkerKind.FINISH, 'gnomish-factory-x7k2q1', at, humanText)
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

    def "parse returns empty for a gnomish-prefixed comment carrying an unrecognized kind (unknown-kind tolerance)"() {
        expect:
        GithubMarker.parse('<!-- gnomish {"kind":"reticulate-splines","instance":"gnomish-factory-x7k2q1",' +
                '"at":"2026-07-20T12:00:00Z","version":1} -->\nhello').isEmpty()
    }

    // FR5 of harden-logging-observability: a comment that opened with the factory's own prefix but
    // cannot be read back is dropped from every marker fold — the claim window, the abort count,
    // the boundary anchor. That is a silent degradation unless it says so.
    def "FR5: a malformed factory-authored marker warns naming what it could not read (#label)"() {
        given:
        def logs = LogCaptureSupport.attach(GithubMarker)

        when:
        def parsed = GithubMarker.parse(body as String)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        parsed.isEmpty()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains(expectedReason as String)

        where:
        label | body | expectedReason
        'unparseable' | '<!-- gnomish {not valid json} -->\nhello' | 'does not parse'
        'missing fields' | '<!-- gnomish {"version":1} -->\nhello' | 'missing kind, instance or at'
        'unknown kind' | '<!-- gnomish {"kind":"reticulate-splines","instance":"gnomish-factory-x7k2q1",' +
                '"at":"2026-07-20T12:00:00Z","version":1} -->\nhello' | 'not a value this version understands'
    }

    // FR5: an operator's own reply is not a degradation — warning on every human comment of every
    // polled issue would drown the very lines above.
    def "FR5: a comment carrying no factory marker is dropped silently (#label)"() {
        given:
        def logs = LogCaptureSupport.attach(GithubMarker)

        when:
        def parsed = GithubMarker.parse(body as String)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        parsed.isEmpty()
        events.isEmpty()

        where:
        label | body
        'human reply' | 'just a human reply, no marker here'
        'blank' | ''
        'other marker' | "<!-- some other tool's marker -->\nhello"
    }

    // FR6: the excerpt is tracker-sourced text, so a marker body cannot forge a second log record.
    def "FR6: a malformed marker cannot forge a second log line"() {
        given:
        def logs = LogCaptureSupport.attach(GithubMarker)
        def forged = '<!-- gnomish {broken\nWARN forged line} -->\nhello'

        when:
        GithubMarker.parse(forged)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        events.size() == 1
        !events[0].formattedMessage.contains('\n')
    }

    def "GithubMarkerKind.PROGRESS round-trips its wire value ('progress')"() {
        expect:
        GithubMarkerKind.PROGRESS.wireValue() == 'progress'
        GithubMarkerKind.fromWireValue('progress') == GithubMarkerKind.PROGRESS
    }

    def "GithubMarkerKind.fromWireValue rejects the retired 'report' wire value"() {
        when:
        GithubMarkerKind.fromWireValue('report')

        then:
        thrown(IllegalArgumentException)
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

    def "an identity-stamped marker round-trips its content identity (FR11)"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')
        def identity = new GithubCommentIdentity('acme/widgets#42', 'park')

        when:
        def body = GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-x7k2q1', at,
                'parked: need a decision', 'escalation', identity, null)
        def parsed = GithubMarker.parse(body)

        then:
        body.contains('"task":"acme/widgets#42","intent":"park"')
        parsed.get().identity() == identity
        parsed.get().reason() == 'escalation'
    }

    def "the identity rides the hidden line only, leaving the human text untouched (NFR-S1)"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')

        when:
        def body = GithubMarker.render(GithubMarkerKind.FINISH, 'gnomish-factory-x7k2q1', at, 'delivered', null,
                new GithubCommentIdentity('acme/widgets#42', 'finish'), null)

        then:
        def lines = body.split('\n', 2)
        lines[0].contains('"intent":"finish"')
        lines[1] == 'delivered'
    }

    def "a marker rendered without an identity keeps the original wire shape and parses with a null identity"() {
        given:
        def at = Instant.parse('2026-07-20T12:00:00Z')

        when:
        def body = GithubMarker.render(GithubMarkerKind.NOTE, 'gnomish-factory-x7k2q1', at, 'note')
        def parsed = GithubMarker.parse(body)

        then:
        !body.contains('"task"')
        !body.contains('"intent"')
        parsed.get().identity() == null
    }

    def "a half-present identity pair parses as no identity at all"() {
        expect:
        GithubMarker.parse('<!-- gnomish {"kind":"note","instance":"gnomish-factory-x7k2q1",' +
                '"at":"2026-07-20T12:00:00Z","version":1,"task":"acme/widgets#42"} -->\nhello')
                .get().identity() == null
    }
}
