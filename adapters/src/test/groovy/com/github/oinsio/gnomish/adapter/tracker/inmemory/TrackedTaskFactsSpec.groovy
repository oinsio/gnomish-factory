package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * TrackedTaskFacts: the reference adapter's fact reporting (FR19 of harden-task-branch-contract) —
 * the label presence its logical state stands for, the claim footprint (live, dead, or none), and
 * the newest boundary entry recorded after the newest claim. Facts only: what the combination means
 * is core's classification, never this adapter's.
 */
class TrackedTaskFactsSpec extends Specification {

    private static final TaskSnapshot SNAPSHOT = new TaskSnapshot('PROJ-1', 'title', 'body')

    def "the #state state stands for the #description label set"() {
        given:
        def task = new TrackedTask(SNAPSHOT, state)

        expect:
        TrackedTaskFacts.labels(task) == labels

        where:
        description | state || labels
        'ready' | new TrackerTaskState.Ready() || StateLabels.readyOnly()
        'working' | new TrackerTaskState.Working('inst-1') || StateLabels.workingOnly()
        'needs-human' | new TrackerTaskState.AwaitingHuman(ParkReason.INFRA) || StateLabels.needsHumanOnly()
        'delivered' | new TrackerTaskState.Finished() || StateLabels.deliveredOnly()
        'closed' | new TrackerTaskState.Gone() || new StateLabels(false, false, false, false, true)
    }

    def "a live claim marker is reported as a live footprint naming its holder"() {
        given:
        def task = working()
        task.establishClaim(marker('inst-1'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-1')

        expect:
        TrackedTaskFacts.claim(task) == new ClaimFacts.Live('inst-1', marker('inst-1').version())
        TrackedTaskFacts.latestBoundary(task) == null
    }

    // A claim cleared with no boundary recorded — a release, or a marker deleted out of band — is a
    // dead footprint: the thread still names who held it, and no live version backs it.
    def "a cleared claim with no boundary after it is a dead footprint"() {
        given:
        def task = working()
        task.establishClaim(marker('inst-1'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-1')
        task.clearClaim()

        expect:
        TrackedTaskFacts.claim(task) == new ClaimFacts.Dead('inst-1')
    }

    // A claim older than the newest boundary belongs to a tenure that boundary already ended: it is
    // no footprint of the current one, so the task reports none.
    def "a claim voided by a later boundary is no footprint at all"() {
        given:
        def task = working()
        task.establishClaim(marker('inst-1'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-1')
        task.clearClaim()
        task.note(CorrespondenceEntry.Kind.ABORT, 'abort: crash')

        expect:
        TrackedTaskFacts.claim(task) == new ClaimFacts.None()
        TrackedTaskFacts.latestBoundary(task) == BoundaryKind.ABORT
    }

    def "a task that was never claimed has no footprint"() {
        expect:
        TrackedTaskFacts.claim(new TrackedTask(SNAPSHOT, new TrackerTaskState.Ready())) == new ClaimFacts.None()
    }

    def "the #kind entry is reported as the #boundary boundary"() {
        given:
        def task = working()
        task.note(kind, 'boundary')

        expect:
        TrackedTaskFacts.latestBoundary(task) == boundary

        where:
        kind || boundary
        CorrespondenceEntry.Kind.ABORT || BoundaryKind.ABORT
        CorrespondenceEntry.Kind.PARK || BoundaryKind.PARK
        CorrespondenceEntry.Kind.FINISH || BoundaryKind.FINISH
        CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED || BoundaryKind.STALE_CLAIM_REMOVED
    }

    def "the non-boundary #kind entry is no boundary of its own"() {
        given:
        def task = working()
        task.note(kind, 'not a boundary')

        expect:
        TrackedTaskFacts.latestBoundary(task) == null

        where:
        kind << [
            CorrespondenceEntry.Kind.CLAIM,
            CorrespondenceEntry.Kind.ACK,
            CorrespondenceEntry.Kind.NOTE,
            CorrespondenceEntry.Kind.PROGRESS,
            CorrespondenceEntry.Kind.HEARTBEAT,
            CorrespondenceEntry.Kind.INDEX_REPAIR
        ]
    }

    // Position decides: a boundary that ended an EARLIER tenure says nothing about the current one,
    // so a claim recorded after it leaves no boundary in the facts.
    def "a boundary older than the newest claim is not reported"() {
        given:
        def task = working()
        task.note(CorrespondenceEntry.Kind.ABORT, 'abort: crash')
        task.establishClaim(marker('inst-2'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-2')

        expect:
        TrackedTaskFacts.latestBoundary(task) == null
        TrackedTaskFacts.claim(task) == new ClaimFacts.Live('inst-2', marker('inst-2').version())
    }

    // The newest boundary wins when several were recorded after the newest claim.
    def "the newest boundary after the claim is the one reported"() {
        given:
        def task = working()
        task.establishClaim(marker('inst-1'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-1')
        task.clearClaim()
        task.note(CorrespondenceEntry.Kind.ABORT, 'abort: crash')
        task.note(CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED, 'stale claim removed')

        expect:
        TrackedTaskFacts.latestBoundary(task) == BoundaryKind.STALE_CLAIM_REMOVED
    }

    def "the whole triple is reported together"() {
        given:
        def task = working()
        task.establishClaim(marker('inst-1'))
        task.note(CorrespondenceEntry.Kind.CLAIM, 'claimed by inst-1')

        expect:
        TrackedTaskFacts.facts(task).labels() == StateLabels.workingOnly()
        TrackedTaskFacts.facts(task).claim() == new ClaimFacts.Live('inst-1', marker('inst-1').version())
        TrackedTaskFacts.facts(task).latestBoundary() == null
    }

    private static TrackedTask working() {
        new TrackedTask(SNAPSHOT, new TrackerTaskState.Working('inst-1'))
    }

    private static ClaimMarker marker(String holder) {
        new ClaimMarker('marker-1', Instant.parse('2026-08-01T10:00:00Z'), holder, null, new ClaimEpoch(1))
    }
}
