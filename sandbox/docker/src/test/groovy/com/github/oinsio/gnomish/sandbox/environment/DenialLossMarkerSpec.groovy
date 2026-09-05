package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR8, NFR-O3, UX3 of fix-denial-attribution-durability: the two losses the factory can actually
 * see, rendered as findings on the same channel the denials use (design D6). A report must be able
 * to say "no data" instead of implying "no denials", and each marker names the window it can bound.
 */
class DenialLossMarkerSpec extends Specification {

    // An environment key (see the glossary), not a credential.
    private static final String KEY = 'gnomish-PROJ-9' // gitleaks:allow

    def "FR8: a saturated tail window names the read window it lost lines from"() {
        expect:
        def marker = DenialLossMarker.tailWindowFull(KEY, 1000, since)
        marker.identity() == null
        marker.finding().message().contains('1000-line window')
        marker.finding().location() == KEY
        marker.finding().details().contains(window)

        where:
        since || window
        null || "the guard container's start"
        '2026-08-19T10:00:00.000000001Z' || '2026-08-19T10:00:00.000000001Z'
    }

    def "FR8: a vanished denial source names both the recorded source and the live one"() {
        expect:
        def marker = DenialLossMarker.sourceGone(KEY, 'sha256:container-gone', live)
        marker.identity() == null
        marker.finding().message().startsWith('egress denials may be lost')
        marker.finding().location() == KEY
        marker.finding().details().contains('sha256:container-gone')
        marker.finding().details().contains(named)

        where:
        live || named
        'sha256:container-live' || 'sha256:container-live'
        null || '(unreadable)'
    }
}
