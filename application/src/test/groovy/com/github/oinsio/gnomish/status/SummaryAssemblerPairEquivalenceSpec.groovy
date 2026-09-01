package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.app.take.TaskSummaryAssembler
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * The declared sync pair of design D8: {@link TaskSummaryAssembler} (serve/take, over a terminal
 * {@code TakeResult}) and {@link SummaryAccumulatorListener} (manual run, over the engine's event
 * stream). Their invariant is that equivalent facts produce an equivalent summary — an operator
 * reading two log files must not have to know which mode wrote which.
 *
 * <p>The two are kept in step by hand because their fact sources genuinely differ, so this spec is
 * what stands in for a compiler: it feeds one {@code TaskState} into both ends through the
 * mode-specific value each one actually consumes and compares the rendered lines. Wall time is
 * excluded from the comparison and only from that — it is measured, not derived, so the two ends
 * cannot agree on it by construction.
 *
 * <p>Implements FR3, D8 of harden-logging-observability.
 */
class SummaryAssemblerPairEquivalenceSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'

    private LogCaptureSupport capture

    def setup() {
        capture = LogCaptureSupport.attach(AnchorLog)
    }

    def cleanup() {
        capture.detach()
    }

    private static TaskState state(Position position = new Position.AtStage('implement')) {
        new TaskState(position, 2, [], new ExecutorUsage(
                Duration.ofSeconds(4), [], ['sonnet': new TokenUsage(11, 22, 33, 44)]))
    }

    /** The rendered summary minus the one component the two ends measure independently. */
    private static String withoutWall(String message) {
        message.replaceFirst(/, wall=[^,]+/, '')
    }

    // D8: one state, both ends, every outcome family the two share. REVOKED is deliberately absent
    // — a manual run holds no claim, so nothing can revoke it (the pair's one asymmetry, recorded
    // at both ends).
    def "both assemblers describe a #family terminal identically"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when: 'the manual-run end assembles from the engine bookends'
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, engineOutcome))

        and: 'the serve/take end assembles from the terminal result of the same run'
        AnchorLog.taskSummary(TaskSummaryAssembler.assemble(takeResult, Duration.ofSeconds(9)))

        then: 'two lines, differing in nothing but the measured wall time'
        capture.list.size() == 2
        withoutWall(capture.list[0].formattedMessage) == withoutWall(capture.list[1].formattedMessage)
        capture.list[0].level == capture.list[1].level

        where:
        family | engineOutcome | takeResult
        'delivered' | new TaskOutcome.Completed(state()) |
                new TakeResult.Delivered(state(), 'done')
        'checkpoint park' | new TaskOutcome.Paused(state(), 'implement') |
                new TakeResult.AwaitingHuman(state(), ParkReason.CHECKPOINT, 'paused at a checkpoint')
        'escalation park' | new TaskOutcome.Escalated(state(), new EscalationReport.AttemptsExhausted(3)) |
                new TakeResult.AwaitingHuman(state(), ParkReason.ESCALATION, 'attempts exhausted')
        'aborted' | new TaskOutcome.Aborted(state(), new AttemptKey(TASK_ID, 'implement', 2), 'push failed') |
                new TakeResult.Aborted(state(), 'push failed')
    }

    // D8: the pair's shape invariant, not just its wording — a task that finished the pipeline
    // must lose its stage on both ends, not on one.
    def "both assemblers render a finished pipeline the same way"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when:
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.PipelineEnd(), 0))
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, new TaskOutcome.Completed(state(new Position.PipelineEnd()))))
        AnchorLog.taskSummary(TaskSummaryAssembler.assemble(
                        new TakeResult.Delivered(state(new Position.PipelineEnd()), 'done'), Duration.ofSeconds(9)))

        then:
        capture.list.size() == 2
        withoutWall(capture.list[0].formattedMessage) == withoutWall(capture.list[1].formattedMessage)
        capture.list[0].formattedMessage.contains('stage=pipelineEnd')
    }
}
