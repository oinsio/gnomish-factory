package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * TrackerObservation.sweep: the union of both listings, which is what keeps a kill window from
 * being filtered out by the very label its sequence had not written yet.
 *
 * FR19 of harden-task-branch-contract.
 */
class TrackerObservationSpec extends Specification {

    private static final TaskRef READY_REF = new TaskRef('T-ready')
    private static final TaskRef OPEN_REF = new TaskRef('T-open')
    private static final ClaimFacts LIVE = new ClaimFacts.Live(
    'inst-1', new ClaimVersion('m-1', Instant.EPOCH, new ClaimEpoch(1)))

    def "the sweep is the union of both listings, ready entries first"() {
        given:
        def ready = [
            readyEntry(READY_REF, new ClaimFacts.None(), false, false)
        ]
        def open = [(OPEN_REF): TrackerFacts.of(StateLabels.workingOnly(), LIVE)]

        when:
        def sweep = TrackerObservation.sweep(ready, open)

        then:
        sweep*.ref() == [READY_REF, OPEN_REF]
        sweep[0].shape() == new TrackerShape.Ready()
        sweep[1].shape() == new TrackerShape.Claimed(LIVE)
    }

    // A ready-labeled task still carrying a live claim is the ghost the sweep exists to catch.
    def "a ready entry carrying a live claim classifies as an abandoned footprint"() {
        when:
        def sweep = TrackerObservation.sweep([
            readyEntry(READY_REF, LIVE, false, false)
        ], [:])

        then:
        sweep*.shape() == [
            new TrackerShape.ClaimAbandoned(LIVE)
        ]
    }

    def "a ready entry's history becomes its boundary fact: finished outranks returned"() {
        expect:
        TrackerObservation.sweep([
            readyEntry(READY_REF, new ClaimFacts.None(), returned, finished)
        ], [:])
        *.shape() == [expected]

        where:
        returned | finished || expected
        false | false || new TrackerShape.Ready()
        true | false || new TrackerShape.Returned()
        false | true || new TrackerShape.Returned()
        true | true || new TrackerShape.Returned()
    }

    // The open listing is the richer observation — labels and boundary, not just history flags — so
    // it wins for a task both listings name (a task wearing both labels mid-claim).
    def "a task in both listings is observed through the open listing's facts"() {
        given:
        def ready = [
            readyEntry(READY_REF, new ClaimFacts.None(), false, false)
        ]
        def open = [(READY_REF): TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None())]

        when:
        def sweep = TrackerObservation.sweep(ready, open)

        then:
        sweep.size() == 1
        sweep[0].shape() == new TrackerShape.ClaimPending()
    }

    private static ReadyTask readyEntry(TaskRef ref, ClaimFacts claim, boolean returned, boolean finished) {
        new ReadyTask(ref, AbortFacts.none(), returned, finished, 'fixture title', claim)
    }
}
