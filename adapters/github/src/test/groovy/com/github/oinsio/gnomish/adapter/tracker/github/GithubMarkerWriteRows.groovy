package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * The factory comments the GitHub adapter writes, one row per marker kind — the kind, the seed of the
 * {@link GithubKillWindowWorld} issue it needs, and the {@link Tracker} operation that writes it — plus
 * the two readings of the thread the marker specs share. One table, so {@code GithubMarkerUpsertSpec}
 * and {@code GithubMarkerPrivacySpec} cannot drift on which writes "every kind" means.
 *
 * <p>Test-only: never shipped.
 */
final class GithubMarkerWriteRows {

    private GithubMarkerWriteRows() {
    }

    static Closure working() {
        { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.KILLER)
        }
    }

    /** One row per marker kind the adapter writes besides the claim: its seed and the operation. */
    static List<List> rows() {
        def abort = new AbortRecord(UntrustedText.subprocess('tests failed'), GithubKillWindowWorld.KILLER,
                Instant.parse('2026-07-20T10:00:00Z'), RecoveryCause.INSTANCE_CRASH)
        [
            [
                GithubMarkerKind.ABORT,
                working(),
                { Tracker t, TaskRef r ->
                    t.recordAbort(r, abort)
                }
            ],
            [
                GithubMarkerKind.ACK,
                { FixtureSeeder s, FixtureIssue i ->
                    s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
                    s.seedReply(i, new HumanReply('go with plan B', Instant.parse('2000-06-01T00:00:00Z')))
                },
                { Tracker t, TaskRef r ->
                    t.acknowledgeDecision(r, 'go with plan B')
                }
            ],
            [
                GithubMarkerKind.NOTE,
                working(),
                { Tracker t, TaskRef r ->
                    t.postNote(r, 'work stopped')
                }
            ],
            [
                GithubMarkerKind.PARK,
                working(),
                { Tracker t, TaskRef r ->
                    t.park(r, ParkReason.ESCALATION, 'need a human')
                }
            ],
            [
                GithubMarkerKind.FINISH,
                working(),
                { Tracker t, TaskRef r ->
                    t.finish(r, 'all stages passed')
                }
            ],
            [
                GithubMarkerKind.PROGRESS,
                working(),
                { Tracker t, TaskRef r ->
                    t.recordProgress(r)
                }
            ],
            [
                GithubMarkerKind.STALE_CLAIM_REMOVED,
                working(),
                { Tracker t, TaskRef r ->
                    t.removeStaleClaim(r, t.listOpen().find {
                        it.ref() == r
                    }.facts().claim())
                }
            ],
            [
                GithubMarkerKind.INDEX_REPAIR,
                { FixtureSeeder s, FixtureIssue i ->
                    s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
                    s.postMarker(i, GithubMarkerKind.ABORT, GithubKillWindowWorld.HOLDER, null, 'aborted')
                },
                { Tracker t, TaskRef r ->
                    t.repairIndex(r, t.listOpen().find {
                        it.ref() == r
                    }.facts())
                }
            ],
        ]
    }

    /** The claim's row: a ready issue claimed, then beaten once, so the re-rendered claim is read too. */
    static List claimRow() {
        [
            GithubMarkerKind.CLAIM,
            { FixtureSeeder s, FixtureIssue i ->
                s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
            },
            { Tracker t, TaskRef r ->
                t.claim(r, GithubKillWindowWorld.KILLER)
                t.heartbeat(r, 'stage build, attempt 1')
            }
        ]
    }

    /** The parsed factory markers of {@code kind} on the issue's thread, in thread order. */
    static List<ParsedMarker> markersOf(FixtureIssue issue, GithubMarkerKind kind) {
        issue.comments()
                .collect { GithubMarker.parse(it.body()) }
                .findAll { it.present && it.get().kind() == kind }*.get()
    }

    /** The one-line JSON inside every factory comment's hidden {@code <!-- gnomish … -->} line. */
    static List<String> hiddenMarkers(FixtureIssue issue) {
        issue.comments()*.body()
                .findAll { it.startsWith('<!-- gnomish ') }
                .collect {
                    it.substring('<!-- gnomish '.length(), it.indexOf(' -->'))
                }
    }
}
