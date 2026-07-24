package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import java.time.Instant
import spock.lang.Specification

/**
 * Direct unit coverage of {@link GithubCommentBoundary}'s pure boundary-folding
 * logic: the class javadoc's two abort-folding cases (latest boundary is ABORT
 * vs. CLAIM) and the "pick the latest timestamp" folding rule within each,
 * which {@code GithubTaskFetcherSpec}'s WireMock-fixture scenarios exercise
 * only incidentally. Kept as a same-package unit spec against the
 * package-private static methods directly, since routing every boundary edge
 * case through an HTTP fixture would be needless machinery for pure logic.
 *
 * <p>Implements FR2, FR5 of add-tracker-port.
 */
class GithubCommentBoundarySpec extends Specification {

    private static ParsedMarker claimMarker(String instance, String at) {
        new ParsedMarker(GithubMarkerKind.CLAIM, instance, Instant.parse(at), 1, 'claimed', null)
    }

    private static ParsedMarker abortMarker(String instance, String at) {
        new ParsedMarker(GithubMarkerKind.ABORT, instance, Instant.parse(at), 1, 'aborted', null)
    }

    def "abortFactsSinceBoundary returns AbortFacts.none when there is no boundary marker at all"() {
        given: 'a marker list with no CLAIM or ABORT marker'
        def markers = []

        expect: 'no aborts are reported'
        GithubCommentBoundary.abortFactsSinceBoundary(markers) == AbortFacts.none()
    }

    def "abortFactsSinceBoundary folds only aborts at or after the latest ABORT boundary, keeping the latest timestamp"() {
        given: 'the boundary (latest-timestamp) abort is list-first, an earlier-timestamp abort follows it'
        def markers = [
            abortMarker('instance-a', '2026-07-20T10:00:00Z'),
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
        ]

        when: 'abort facts are folded from the boundary'
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'both aborts fold in (both are at-or-after the boundary index), keeping the later timestamp'
        facts.count() == 2
        facts.lastAbortAt() == Instant.parse('2026-07-20T10:00:00Z')
    }

    def "abortFactsSinceBoundary folds the abort streak before an active claim by the same holder, keeping the latest timestamp"() {
        given: 'two aborts by the same holder immediately before its active claim'
        def markers = [
            abortMarker('instance-a', '2026-07-20T08:00:00Z'),
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
            claimMarker('instance-a', '2026-07-20T10:00:00Z'),
        ]

        when: 'abort facts are folded from the boundary'
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'both aborts in the streak fold in, keeping the later of the two timestamps'
        facts.count() == 2
        facts.lastAbortAt() == Instant.parse('2026-07-20T09:00:00Z')
    }

    def "abortFactsSinceBoundary stops the streak at a different instance's claim"() {
        given: 'an abort by instance-a, then a claim by a different instance-b'
        def markers = [
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
            claimMarker('instance-b', '2026-07-20T10:00:00Z'),
        ]

        when: 'abort facts are folded from the boundary'
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'the stale abort from the previous holder is excluded — a fresh claimant inherits nothing'
        facts == AbortFacts.none()
    }
}
