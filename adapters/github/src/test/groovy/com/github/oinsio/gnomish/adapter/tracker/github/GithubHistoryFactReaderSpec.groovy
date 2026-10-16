package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * GithubHistoryFactReader#derive / #deriveFinished (FR1, FR2 of
 * enforce-finish-terminality): {@code returned} must be true only for a PARK
 * or STALE_CLAIM_REMOVED marker — never for a FINISH marker — and {@code
 * finished} must be true only for a FINISH marker. The two facts are derived
 * independently over the full marker history, so a finish-then-reopen thread
 * never reports {@code returned = true}, and a task that was both parked and
 * later finished reports both facts true.
 */
class GithubHistoryFactReaderSpec extends Specification {

    private static ParsedMarker marker(GithubMarkerKind kind) {
        new ParsedMarker(kind, 'gnomish-factory-a1', Instant.parse('2026-07-20T11:00:00Z'), 1, UntrustedText.tracker(''), null, null, null)
    }

    def "an empty marker list yields both facts false"() {
        expect:
        !GithubHistoryFactReader.derive([])
        !GithubHistoryFactReader.deriveFinished([])
    }

    def "a STALE_CLAIM_REMOVED marker yields returned=true, finished=false"() {
        given:
        def markers = [
            marker(GithubMarkerKind.STALE_CLAIM_REMOVED)
        ]

        expect:
        GithubHistoryFactReader.derive(markers)
        !GithubHistoryFactReader.deriveFinished(markers)
    }

    // Scenario: tracker/github-tracker / Finish marker is not returned
    def "a FINISH marker yields finished=true, returned=false (finish-then-reopen)"() {
        given: 'a task that was finished, then a human reopened the issue — no new marker is written on reopen'
        def markers = [
            marker(GithubMarkerKind.FINISH)
        ]

        expect:
        GithubHistoryFactReader.deriveFinished(markers)
        !GithubHistoryFactReader.derive(markers)
    }

    def "a PARK marker yields returned=true, finished=false (park-then-reopen)"() {
        given: 'a task that was parked, then a human reopened the issue back to Ready'
        def markers = [marker(GithubMarkerKind.PARK)]

        expect:
        GithubHistoryFactReader.derive(markers)
        !GithubHistoryFactReader.deriveFinished(markers)
    }

    def "both a PARK and a later FINISH marker yield returned=true AND finished=true"() {
        given: 'a task parked once, resumed, and later finished'
        def markers = [
            marker(GithubMarkerKind.PARK),
            marker(GithubMarkerKind.FINISH)
        ]

        expect:
        GithubHistoryFactReader.derive(markers)
        GithubHistoryFactReader.deriveFinished(markers)
    }

    // Scenario: tracker/github-tracker / Never-finished issue does not report finished
    def "a CLAIM/ABORT/PROGRESS-only thread yields both facts false"() {
        given:
        def markers = [
            marker(GithubMarkerKind.CLAIM),
            marker(GithubMarkerKind.ABORT),
            marker(GithubMarkerKind.PROGRESS)
        ]

        expect:
        !GithubHistoryFactReader.derive(markers)
        !GithubHistoryFactReader.deriveFinished(markers)
    }

    // FR1 of fix-terminal-receipt-convergence: the tenure boundary is the thread's last boundary
    //     marker, whatever claims follow it — the claims a pickup posts after a park never hide it
    def "the tenure boundary is the last boundary marker, whatever claims follow it"() {
        expect:
        GithubHistoryFactReader.deriveTenureBoundary(kinds.collect {
            marker(it)
        }) == expected

        where:
        kinds | expected
        [] | null
        [
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.PROGRESS,
            GithubMarkerKind.ACK,
            GithubMarkerKind.NOTE
        ] | null
        [
            GithubMarkerKind.PARK,
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.CLAIM
        ] | BoundaryKind.PARK
        [
            GithubMarkerKind.ABORT,
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.PARK,
            GithubMarkerKind.CLAIM
        ] | BoundaryKind.PARK
        [
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.FINISH,
            GithubMarkerKind.INDEX_REPAIR
        ] | BoundaryKind.FINISH
        [
            GithubMarkerKind.PARK,
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.ABORT
        ] | BoundaryKind.ABORT
        [
            GithubMarkerKind.PARK,
            GithubMarkerKind.STALE_CLAIM_REMOVED,
            GithubMarkerKind.CLAIM
        ] | BoundaryKind.STALE_CLAIM_REMOVED
    }
}
