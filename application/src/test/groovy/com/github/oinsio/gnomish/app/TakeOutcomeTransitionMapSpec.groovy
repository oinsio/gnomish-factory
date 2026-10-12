package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.app.take.ParkTransition
import com.github.oinsio.gnomish.app.take.TerminalTransitions
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
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
import java.time.Instant
import spock.lang.Specification

/**
 * The outcome-to-transition mapping as the tracker receives it (FR2, FR9, FR12 of add-tracker-port):
 * each way an engine run can end reaches the port as exactly one write — {@code finish}, {@code
 * park} with the reason the escalation kind picks, {@code park(CHECKPOINT)}, or {@code recordAbort}
 * below the fuse and {@code park(INFRA)} at it — and an infrastructure park that comes from an
 * escalation never touches the abort counter.
 */
class TakeOutcomeTransitionMapSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskContext CONTEXT = new TaskContext(
    'PROJ-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker('body'), List.<Decision> of())
    static final TaskState STATE = TaskState.atStageStart('build')
    static final int FUSE = 3

    Tracker tracker = Mock()
    /** The abort history the task's fetch reports; the abort features set it before dispatching. */
    AbortFacts priorAborts = AbortFacts.none()

    def setup() {
        tracker.fetchTask(REF) >> {
            new TrackerTask(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
            new TrackerTaskState.Working(INSTANCE.value()), priorAborts, false)
        }
    }

    private void dispatch(TaskOutcome outcome) {
        def dispatch = new TakeOutcomeDispatch(
                VirtualTimeRetries.terminalWrite(), new AbortFuse(new AbortHandler(tracker, new VirtualClock()), FUSE))
        def transitions = new TerminalTransitions(
                new ParkTransition.Fresh({
                    new ParkDeliveryVerdict.Delivered()
                } as ParkTransition.ParkIntent, {}),
                new FinishTransition.Fresh({}, {}))
        dispatch.dispatch(outcome, CONTEXT, 'gnomish/PROJ-1', TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE), transitions)
    }

    // FR2, FR12 of add-tracker-port: Escalated parks with the reason its kind picks — and an infra
    //     park from an escalation leaves the abort counter untouched
    // Scenario: tracker/tracker-port / Outcome-to-transition mapping
    def "an escalation for #kind is parked as #reason without touching the abort counter"() {
        when:
        dispatch(new TaskOutcome.Escalated(STATE, report))

        then: 'one park with the picked reason, and nothing else written'
        1 * tracker.park(REF, reason, _ as String)
        0 * tracker.recordAbort(*_)
        0 * tracker.recordProgress(*_)
        0 * tracker.finish(*_)

        where:
        report || reason
        new EscalationReport.AttemptsExhausted(3) || ParkReason.ESCALATION
        new EscalationReport.DecisionNeeded(UntrustedText.agent('proceed?'), [UntrustedText.agent('yes')]) || ParkReason.ESCALATION
        new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('tests')),
                UntrustedText.subprocess('timeout'), UntrustedText.subprocess('')) || ParkReason.INFRA
        new EscalationReport.CannotExecute(UntrustedText.subprocess('executor crashed'), []) || ParkReason.INFRA
        new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('stale-stage')) || ParkReason.INFRA
        kind = report.getClass().simpleName
    }

    // FR2: Completed finishes, Paused parks at its checkpoint — one write each
    // Scenario: tracker/tracker-port / Outcome-to-transition mapping
    def "Completed is finished and Paused is parked at its checkpoint"() {
        when:
        dispatch(new TaskOutcome.Completed(new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())))

        then:
        1 * tracker.finish(REF, _ as String)
        0 * tracker.park(*_)

        when:
        dispatch(new TaskOutcome.Paused(STATE, 'build'))

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _ as String)
        0 * tracker.finish(*_)
    }

    // FR9, FR12: Aborted is recorded below the fuse and parked as INFRA at it
    // Scenario: tracker/tracker-port / Outcome-to-transition mapping
    def "Aborted with #prior earlier aborts is #expected"() {
        given:
        priorAborts = prior == 0 ? AbortFacts.none() : new AbortFacts(prior, Instant.EPOCH)

        when:
        dispatch(new TaskOutcome.Aborted(STATE, new AttemptKey('PROJ-1', 'build', 0), UntrustedText.subprocess('lost')))

        then:
        recorded * tracker.recordAbort(REF, _)
        parked * tracker.park(REF, ParkReason.INFRA, _ as String)
        0 * tracker.finish(*_)

        where:
        prior || recorded | parked
        0 || 1 | 0
        FUSE - 1 || 0 | 1
        expected = recorded ? 'recorded as an abort' : 'parked as INFRA at the fuse'
    }
}
