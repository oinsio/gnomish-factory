package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import spock.lang.Specification

/**
 * FR3 of harden-logging-observability, manual-run delta scenario "Manual run ends with the
 * summary": a manual run has no terminal {@code TakeResult} to map, so its summary is assembled
 * from the engine's own run bookends — and it must come out in the identical form the serve/take
 * end produces, for every terminal outcome a manual run can reach.
 */
class SummaryAccumulatorListenerSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'

    private LogCaptureSupport capture

    def setup() {
        capture = LogCaptureSupport.attach(AnchorLog)
    }

    def cleanup() {
        capture.detach()
    }

    private static TaskState state(int attemptsUsed = 2, Position position = new Position.AtStage('implement')) {
        new TaskState(position, attemptsUsed, [], new ExecutorUsage(
                Duration.ofSeconds(4), [], ['sonnet': new TokenUsage(11, 22, 33, 44)]))
    }

    private static void run(SummaryAccumulatorListener listener, TaskOutcome outcome) {
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))
    }

    // FR3: every terminal outcome a manual run can reach produces exactly one summary, at the
    // level that outcome warrants, in the shared form.
    def "a manual run ends with one summary line for a #outcome terminal"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when:
        run(listener, outcome)

        then:
        capture.list.size() == 1
        capture.list[0].level == level

        and:
        String message = capture.list[0].formattedMessage
        message.contains("task summary: outcome=${word}")
        message.contains('stage=implement')
        message.contains('attempts=2')
        message.contains('tokens={sonnet=11/22/33/44}')

        where:
        outcome | word | level
        new TaskOutcome.Completed(state()) | 'delivered' | Level.INFO
        new TaskOutcome.Paused(state(), 'implement') | 'awaitingHuman' | Level.INFO
        new TaskOutcome.Escalated(state(), new EscalationReport.AttemptsExhausted(3)) | 'awaitingHuman' | Level.INFO
        new TaskOutcome.Aborted(state(), new AttemptKey(TASK_ID, 'implement', 2), 'push failed') | 'aborted' | Level.WARN
    }

    // FR3: the wall time is the elapsed run — the difference between the two bookends, never a raw
    // monotonic reading. A summary reporting the clock's own origin instead would hand the operator
    // a duration that looks like a fact and is off by however long the machine has been up.
    def "the wall time is the run's own elapsed time, not a raw monotonic reading"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when:
        run(listener, new TaskOutcome.Completed(state()))

        then:
        def matcher = capture.list[0].formattedMessage =~ /wall=([^,]+)/
        matcher.find()
        def wall = Duration.parse(matcher.group(1))
        !wall.negative
        wall <Duration.ofMinutes(1)
    }

    // FR3, design D8: the two parks are distinguished by the same reason names the serve/take end
    // reads off the tracker port, so an identical situation reads identically in either mode.
    def "the two park families name their reason the way the tracker port does"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when:
        run(listener, new TaskOutcome.Paused(state(), 'implement'))
        run(listener, new TaskOutcome.Escalated(state(), new EscalationReport.AttemptsExhausted(3)))

        then:
        capture.list[0].formattedMessage.contains('(CHECKPOINT)')
        capture.list[1].formattedMessage.contains('(ESCALATION)')
    }

    // FR3: a run that finished the pipeline names the position rather than rendering a bare null.
    def "a completed pipeline renders its position as pipelineEnd"() {
        given:
        def listener = new SummaryAccumulatorListener()

        when:
        run(listener, new TaskOutcome.Completed(state(1, new Position.PipelineEnd())))

        then:
        capture.list[0].formattedMessage.contains('stage=pipelineEnd')
    }

    // The listener's other five events are not its business: a run that emitted attempts and
    // checks along the way still ends with exactly one line, not one per event.
    def "no event other than the run bookends produces a line"() {
        given:
        def listener = new SummaryAccumulatorListener()
        def key = new AttemptKey(TASK_ID, 'implement', 1)

        when:
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.AttemptStarted(key))
        listener.onEvent(new EngineEvent.CheckStarted(key, new CheckRef(0, 'files_exist')))
        listener.onEvent(new EngineEvent.CheckFinished(key,
                new CheckResult(new CheckRef(0, 'files_exist'), new Verdict.Pass(), Duration.ZERO)))
        listener.onEvent(new EngineEvent.ExecutionFinished(key, ExecutorUsage.none()))
        listener.onEvent(new EngineEvent.AttemptFinished(key, state(), new ToolTrace(key, [])))
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, new TaskOutcome.Completed(state())))

        then:
        capture.list.size() == 1
    }
}
