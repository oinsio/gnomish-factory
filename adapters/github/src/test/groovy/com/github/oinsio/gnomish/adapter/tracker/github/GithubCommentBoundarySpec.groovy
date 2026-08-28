package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
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
        new ParsedMarker(GithubMarkerKind.CLAIM, instance, Instant.parse(at), 1, 'claimed', null, null, null)
    }

    private static ParsedMarker abortMarker(String instance, String at) {
        new ParsedMarker(GithubMarkerKind.ABORT, instance, Instant.parse(at), 1, 'aborted', null, null, null)
    }

    /** An ABORT marker categorized as a failed branch repair (FR14 of harden-task-branch-contract). */
    private static ParsedMarker recoveryAbortMarker(String instance, String at) {
        new ParsedMarker(GithubMarkerKind.ABORT, instance, Instant.parse(at), 1, 'aborted',
                RecoveryCause.RECOVERY_FAILURE.wireValue(), null, null)
    }

    private static ParsedMarker progressMarker(String instance, String at) {
        new ParsedMarker(GithubMarkerKind.PROGRESS, instance, Instant.parse(at), 1, 'progressed', null, null, null)
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

    def "abortFactsSinceBoundary keeps folding across the holder's own self-reclaim within one streak"() {
        given: 'the same holder aborts, self-reclaims, aborts again, then holds the active claim — one streak'
        def markers = [
            abortMarker('instance-a', '2026-07-20T08:00:00Z'),
            claimMarker('instance-a', '2026-07-20T08:30:00Z'),
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
            claimMarker('instance-a', '2026-07-20T10:00:00Z'),
        ]

        when: 'abort facts are folded backward from the active claim'
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'the self-reclaim CLAIM is skipped (not a stop), so BOTH earlier aborts stay in the streak'
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

    def "abortFactsSinceBoundary resets to zero when a PROGRESS marker has no ABORT after it"() {
        given: 'aborts before a PROGRESS marker, none after'
        def markers = [
            claimMarker('instance-a', '2026-07-20T08:00:00Z'),
            abortMarker('instance-a', '2026-07-20T08:30:00Z'),
            claimMarker('instance-a', '2026-07-20T09:00:00Z'),
            progressMarker('instance-a', '2026-07-20T10:00:00Z'),
        ]

        expect: 'the durable round resets the streak to zero, ignoring the pre-progress aborts'
        GithubCommentBoundary.abortFactsSinceBoundary(markers) == AbortFacts.none()
    }

    def "abortFactsSinceBoundary counts only ABORT markers strictly after the latest PROGRESS marker"() {
        given: 'a PROGRESS marker followed by two ABORT markers'
        def markers = [
            claimMarker('instance-a', '2026-07-20T08:00:00Z'),
            abortMarker('instance-a', '2026-07-20T08:30:00Z'),
            progressMarker('instance-a', '2026-07-20T09:00:00Z'),
            abortMarker('instance-a', '2026-07-20T10:00:00Z'),
            abortMarker('instance-a', '2026-07-20T11:00:00Z'),
        ]

        when: 'abort facts are folded from the boundary'
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'only the two post-progress aborts count, keeping the latest timestamp'
        facts.count() == 2
        facts.lastAbortAt() == Instant.parse('2026-07-20T11:00:00Z')
    }

    def "abortFactsSinceBoundary excludes an ABORT marker at or immediately before the PROGRESS marker"() {
        given: 'an ABORT marker at the same list position ordering immediately preceding PROGRESS'
        def markers = [
            claimMarker('instance-a', '2026-07-20T08:00:00Z'),
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
            progressMarker('instance-a', '2026-07-20T09:30:00Z'),
        ]

        expect: 'the immediately-preceding abort is at-or-before the boundary, so it is not counted'
        GithubCommentBoundary.abortFactsSinceBoundary(markers) == AbortFacts.none()
    }

    // FR14 of harden-task-branch-contract: one counter, two categories. The marker's reason field
    // carries the category, so the recovery share is folded out of the total — post-PROGRESS arm.
    def "abortFactsSinceBoundary splits the post-progress aborts into the recovery share and the crash remainder"() {
        given: 'after the progress marker: one failed branch repair, one uncategorized (crash) abort'
        def markers = [
            progressMarker('instance-a', '2026-07-20T08:00:00Z'),
            recoveryAbortMarker('instance-a', '2026-07-20T09:00:00Z'),
            abortMarker('instance-a', '2026-07-20T09:30:00Z'),
        ]

        when:
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then:
        facts.count() == 2
        facts.recoveryCount() == 1
        facts.crashCount() == 1
    }

    // FR14: the same split on the other arm — the abort streak scanned backward from an active claim.
    def "abortFactsSinceBoundary splits the pre-claim abort streak into the recovery share and the crash remainder"() {
        given: 'three aborts before the holder\'s active claim, only the earliest a failed repair'
        def markers = [
            recoveryAbortMarker('instance-a', '2026-07-20T08:00:00Z'),
            abortMarker('instance-a', '2026-07-20T08:30:00Z'),
            abortMarker('instance-a', '2026-07-20T09:00:00Z'),
            claimMarker('instance-a', '2026-07-20T10:00:00Z'),
        ]

        when:
        def facts = GithubCommentBoundary.abortFactsSinceBoundary(markers)

        then: 'the shares are asymmetric, so the split cannot hold by coincidence'
        facts.count() == 3
        facts.recoveryCount() == 1
        facts.crashCount() == 2
    }

    def "latestBoundaryIndex and activeClaim ignore a PROGRESS marker entirely"() {
        given: 'a PROGRESS marker sitting after the active claim'
        def markers = [
            claimMarker('instance-a', '2026-07-20T08:00:00Z'),
            progressMarker('instance-a', '2026-07-20T09:00:00Z'),
        ]

        expect: 'the claim, not the progress marker, remains the latest boundary and the active claim'
        GithubCommentBoundary.latestBoundaryIndex(markers) == Optional.of(0)
        GithubCommentBoundary.activeClaim(markers).isPresent()
        GithubCommentBoundary.activeClaim(markers).get().instance() == 'instance-a'
    }

    def "latestBoundaryIndex and activeClaim resolve to empty for a PROGRESS-only marker list"() {
        given: 'no CLAIM or ABORT marker, only a PROGRESS marker'
        def markers = [
            progressMarker('instance-a', '2026-07-20T09:00:00Z')
        ]

        expect: 'a PROGRESS marker alone never reads as an active claim or boundary'
        GithubCommentBoundary.latestBoundaryIndex(markers).isEmpty()
        GithubCommentBoundary.activeClaim(markers).isEmpty()
    }

    def "latestProgressIndex picks the latest PROGRESS marker by timestamp, not list position"() {
        given: 'three PROGRESS markers out of timestamp order in the list'
        def markers = [
            progressMarker('instance-a', '2026-07-20T09:00:00Z'),
            progressMarker('instance-a', '2026-07-20T11:00:00Z'),
            progressMarker('instance-a', '2026-07-20T10:00:00Z'),
        ]

        expect: 'the middle marker (latest timestamp) is picked despite not being list-last'
        GithubCommentBoundary.latestProgressIndex(markers) == Optional.of(1)
    }
}
