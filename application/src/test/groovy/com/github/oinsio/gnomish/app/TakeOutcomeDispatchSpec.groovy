package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.app.take.ParkTransition
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.app.take.TerminalTransitions
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link TakeOutcomeDispatch}: the slot's exhaustive outcome dispatch, one feature per arm — each
 * outcome takes exactly its own transition and its own tracker write, and none of the others — and
 * the null-half refusal of the {@link TerminalTransitions} it routes into.
 *
 * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard (design D22); FR9, FR12,
 * FR13, FR18, D11, D12 of add-tracker-port.
 */
class TakeOutcomeDispatchSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskContext CONTEXT = new TaskContext(
    'PROJ-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker('body'), List.<Decision> of())
    static final TaskState STATE = TaskState.atStageStart('build')
    static final String BRANCH = 'gnomish/PROJ-1'

    Tracker tracker = Mock()
    int parkIntents = 0
    int parkReceipts = 0
    int finishIntents = 0
    int finishCleanups = 0

    def setup() {
        tracker.fetchTask(REF) >> TrackerTaskFixtures.taskWith(REF, new TrackerTaskState.Working(INSTANCE.value()))
    }

    private TakeResult dispatch(TaskOutcome outcome) {
        def dispatch = new TakeOutcomeDispatch(
                VirtualTimeRetries.terminalWrite(), new AbortFuse(new AbortHandler(tracker, new VirtualClock()), 3))
        dispatch.dispatch(outcome, CONTEXT, BRANCH, TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE), transitions())
    }

    private TerminalTransitions transitions() {
        new TerminalTransitions(
                new ParkTransition.Fresh({
                    parkIntents++
                    new ParkDeliveryVerdict.Delivered()
                } as ParkTransition.ParkIntent, { parkReceipts++ }),
                new FinishTransition.Fresh({
                    finishIntents++
                }, {
                    finishCleanups++
                }))
    }

    def "an Aborted outcome goes through the abort fuse and takes neither transition"() {
        when:
        def result = dispatch(new TaskOutcome.Aborted(STATE, new AttemptKey('PROJ-1', 'build', 0), UntrustedText.subprocess('lost')))

        then: 'the abort facts come from one fresh fetch of the task'
        1 * tracker.fetchTask(REF) >> TrackerTaskFixtures.taskWith(REF, new TrackerTaskState.Working(INSTANCE.value()))

        then: 'below the fuse: the abort is recorded, nothing is parked or finished'
        1 * tracker.recordAbort(REF, _)
        0 * tracker.park(*_)
        0 * tracker.finish(*_)
        result instanceof TakeResult.Aborted

        and:
        [
            parkIntents,
            parkReceipts,
            finishIntents,
            finishCleanups
        ] == [0, 0, 0, 0]
    }

    def "an Escalated outcome is parked for escalation through the park transition"() {
        when:
        def result = dispatch(new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3)))

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, _ as String)
        0 * tracker.finish(*_)
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION

        and: 'the park intent and its receipt ran once; the finish transition never'
        [
            parkIntents,
            parkReceipts,
            finishIntents,
            finishCleanups
        ] == [1, 1, 0, 0]
    }

    def "a Completed outcome is finished through the finish transition"() {
        given:
        def finalState = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when:
        def result = dispatch(new TaskOutcome.Completed(finalState))

        then:
        1 * tracker.finish(REF, { String summary -> summary.contains(BRANCH) })
        0 * tracker.park(*_)
        result instanceof TakeResult.Delivered

        and: 'the finish intent and its cleanup ran once; the park transition never'
        [
            parkIntents,
            parkReceipts,
            finishIntents,
            finishCleanups
        ] == [0, 0, 1, 1]
    }

    def "a Paused outcome is parked at its checkpoint through the park transition"() {
        when:
        def result = dispatch(new TaskOutcome.Paused(STATE, 'build'))

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, { String report ->
            report.contains(BRANCH)
        })
        0 * tracker.finish(*_)
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT

        and:
        [
            parkIntents,
            parkReceipts,
            finishIntents,
            finishCleanups
        ] == [1, 1, 0, 0]
    }

    def "the terminal transitions refuse a null half"() {
        when:
        new TerminalTransitions(park, finish)

        then:
        def e = thrown(NullPointerException)
        e.message == missing

        where:
        park | finish || missing
        null | new FinishTransition.Fresh({}, {}) || 'park'
        new ParkTransition.Fresh({
            new ParkDeliveryVerdict.Delivered()
        }, {}) | null || 'finish'
    }

    def "the terminal transitions carry the halves they were built with"() {
        given:
        def park = new ParkTransition.Fresh({
            new ParkDeliveryVerdict.Delivered()
        }, {})
        def finish = new FinishTransition.Fresh({}, {})

        expect:
        with(new TerminalTransitions(park, finish)) {
            it.park().is(park)
            it.finish().is(finish)
        }
    }
}
