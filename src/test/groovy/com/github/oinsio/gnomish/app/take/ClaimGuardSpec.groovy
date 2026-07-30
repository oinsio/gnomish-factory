package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import spock.lang.Specification

/**
 * FR7, design D6 of add-claim-heartbeat: the cheap "claim still ours" pre-write check that fences
 * the git-unfenced tracker writes. "Still ours" is exactly {@code Working} held by this instance —
 * every other state or holder is a moved claim the caller must not overwrite.
 */
class ClaimGuardSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    private Tracker tracker = Mock()

    private static TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none())
    }

    // FR7: the only "still ours" verdict is Working held by this instance's own id.
    def "stillOurs is true only when the task is Working held by this instance"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))

        expect:
        ClaimGuard.stillOurs(tracker, REF, INSTANCE)
    }

    // FR7: a foreign holder or any non-Working state is NOT ours — the write must be skipped.
    def "stillOurs is false for #label"() {
        given:
        tracker.fetchTask(REF) >> taskWith(state)

        expect:
        !ClaimGuard.stillOurs(tracker, REF, INSTANCE)

        where:
        label                       | state
        'Working by another'        | new TrackerTaskState.Working('other-instance-xyz')
        'Ready'                     | new TrackerTaskState.Ready()
        'AwaitingHuman'             | new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        'Finished'                  | new TrackerTaskState.Finished()
        'Gone'                      | new TrackerTaskState.Gone()
    }

    // NFR-P1: the check is exactly one fetchTask read (the adapter's ETag cache makes it a free 304).
    def "stillOurs performs exactly one fetchTask read"() {
        when:
        ClaimGuard.stillOurs(tracker, REF, INSTANCE)

        then:
        1 * tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
    }
}
