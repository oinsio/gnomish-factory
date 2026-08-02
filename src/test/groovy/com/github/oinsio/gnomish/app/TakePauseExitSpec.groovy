package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * FR13, FR18, D12 of add-tracker-port and FR7 of add-claim-heartbeat: a fresh {@code Paused}
 * checkpoint parks the task as {@code AwaitingHuman(CHECKPOINT)} — but the park is git-unfenced, so
 * {@link TakePauseExit} guards it with a "claim still ours" pre-write check. When the claim was
 * reaped or taken over mid-run the checkpoint park is skipped (no overwrite of the new holder's
 * state); the run still returns the mapped result.
 */
class TakePauseExitSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'Fix the widget', 'body', List.<Decision> of())
    static final TaskState STATE = TaskState.atStageStart('verify')
    static final String BRANCH = 'gnomish/PROJ-1'

    Tracker tracker = Mock()

    private static TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none(), false)
    }

    // FR13, FR18, D12: the checkpoint park is written for real when the claim is still ours.
    def "finish parks CHECKPOINT with a rendered report when the claim is still ours"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        def paused = new TaskOutcome.Paused(STATE, 'build')

        when:
        def result = TakePauseExit.finish(paused, CONTEXT, BRANCH, tracker, REF, INSTANCE)

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, { String report ->
            report.contains('PROJ-1') &&
            report.contains(BRANCH) &&
            report.toLowerCase().contains('checkpoint') &&
            report.toLowerCase().contains('ready')
        })

        and:
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.CHECKPOINT
        awaiting.report().contains(BRANCH)
    }

    // FR7 of add-claim-heartbeat: a claim reaped/taken over mid-run must not overwrite the new
    // holder's state — the pre-write guard skips the checkpoint park.
    def "finish skips the checkpoint park when the claim is no longer ours (#state)"() {
        given:
        tracker.fetchTask(REF) >> taskWith(state)
        def paused = new TaskOutcome.Paused(STATE, 'build')

        when:
        def result = TakePauseExit.finish(paused, CONTEXT, BRANCH, tracker, REF, INSTANCE)

        then: 'no park is written'
        0 * tracker.park(*_)

        and: 'the run still returns the mapped AwaitingHuman(CHECKPOINT) result'
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT

        where:
        state << [
            new TrackerTaskState.Working('other-instance-xyz'),
            new TrackerTaskState.Ready(),
            new TrackerTaskState.Gone(),
        ]
    }
}
