package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * FR7 of fix-denial-attribution-durability: the identity a denial source assigns one event, and
 * the wrapper that carries it beside the finding without touching the shared {@link Finding} type.
 *
 * <p>Identity is what turns a lost read position from a doubled report into a clean merge, so a
 * blank component must fail loudly: an identity that names no source, or no event, matches nothing
 * and would silently degrade every merge into "keep everything".
 */
class DenialIdentitySpec extends Specification {

    def "FR7: an identity pairs the source with its own event timestamp"() {
        given:
        def identity = new DenialIdentity('sha256:container-1', '2026-08-19T10:00:00.000000001Z')

        expect:
        identity.source() == 'sha256:container-1'
        identity.eventAt() == '2026-08-19T10:00:00.000000001Z'
    }

    def "FR7: a blank component is refused rather than silently matching nothing"() {
        when:
        new DenialIdentity(source, eventAt)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "DenialIdentity.${component} must not be blank"

        where:
        source | eventAt || component
        '' | 'at' || 'source'
        '   ' | 'at' || 'source'
        'src' | '' || 'eventAt'
        'src' | '  ' || 'eventAt'
    }

    // FR7: "unknown, keep" — a denial no source stamped matches nothing on a merge, so it is
    //     attached rather than dropped (design D3: duplicates over silence)
    def "FR7: an unidentified denial carries the finding and no identity"() {
        given:
        def finding = new Finding('egress denied: evil.example.com:443', null, null)

        expect:
        Denial.unidentified(finding).finding() == finding
        Denial.unidentified(finding).identity() == null
    }

    // The shared Finding type is untouched (design D5): every report surface that shows denials
    // reads them through this projection, so identity cannot leak into status.json or the render
    def "FR7: the findings projection drops the identity every report surface must not show"() {
        given:
        def first = new Finding('egress denied: a.example.com:443', null, null)
        def second = new Finding('egress denied: b.example.com:443', null, null)
        def denials = [
            new Denial(first, new DenialIdentity('src', '2026-08-19T10:00:00Z')),
            Denial.unidentified(second)
        ]

        expect:
        Denial.findings(denials) == [first, second]
    }

    def "FR7: two denials to one destination stay two events with distinct identities"() {
        given:
        def finding = new Finding('egress denied: evil.example.com:443', null, null)
        def first = new Denial(finding, new DenialIdentity('src', '2026-08-19T10:00:00.000000001Z'))
        def second = new Denial(finding, new DenialIdentity('src', '2026-08-19T10:00:02.000000001Z'))

        expect:
        first.finding() == second.finding()
        first != second
    }
}
