package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, FR10, FR12 of make-checkpoint-gate-durable; FR11 of add-manual-run: {@code gnomish status
 * --json} over a real task branch whose tip the real repository wrote — a park consumed by a
 * decision, an approval or a resumed write, with the process dying right after, reads as no
 * outcome at all, and the position is the one the continuation moved to. Every other tip names one
 * of the four outcome kinds. Nothing is stubbed between the branch and the printed document.
 */
class StatusConsumedParkSpec extends Specification implements SeededCloneFixture {

    private static final String TASK = 'PARK-1'
    private static final Position.AwaitingApproval GATE = new Position.AwaitingApproval('implement')
    private static final EscalationReport EXHAUSTED = new EscalationReport.AttemptsExhausted(1)

    @TempDir
    Path tempDir

    /** The host medium's writer, claimless as a read-only command's seeding is. */
    TaskLifecycleStore repository

    def setup() {
        setupSeededClone()
        repository = TaskGitFixture.realClaimless().store().taskRepository(registeredClone)
    }

    /** The state a passing {@code manual} round leaves: at the gate, its passing round recorded. */
    private static TaskState gated() {
        new TaskState(GATE, 0, [
            new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(),
            JudgeUsage.none(), [], Stop.none())
        ], ExecutorUsage.none())
    }

    private void create(TaskState state) {
        repository.createTask(new TaskContext(TASK, UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), state)
    }

    /** Writes the tip {@code shape} names, through the repository's own writers, and nothing after it. */
    private void tip(String shape) {
        def implement = TaskState.atStageStart('implement')
        switch (shape) {
            case 'mid-run':
                create(implement)
                break
            case 'decision':
                create(implement)
                repository.recordOutcome(TASK, new TaskOutcome.Escalated(implement, EXHAUSTED), TrackerWrite.OWED)
                repository.appendDecision(TASK, new Decision('patch it', 'implement', 'operator', Instant.EPOCH),
                        implement.resetAttempts())
                break
            case 'approval':
                create(gated())
                repository.recordOutcome(TASK, new TaskOutcome.Paused(gated(), 'implement'), TrackerWrite.OWED)
                def approved = new TaskState(new Position.AtStage('verify'), 0, gated().attempts(), ExecutorUsage.none())
                repository.approveCheckpoint(TASK, GATE, approved)
                break
            case 'resumed write':
                create(implement)
                repository.recordOutcome(TASK, new TaskOutcome.Escalated(implement, EXHAUSTED), TrackerWrite.OWED)
                repository.resumeFrom(TASK, implement.resetAttempts())
                break
            case 'completed':
                create(implement)
                repository.recordOutcome(TASK, new TaskOutcome.Completed(implement), TrackerWrite.OWED)
                break
            case 'paused':
                create(gated())
                repository.recordOutcome(TASK, new TaskOutcome.Paused(gated(), 'implement'), TrackerWrite.OWED)
                break
            case 'escalated':
                create(implement)
                repository.recordOutcome(TASK, new TaskOutcome.Escalated(implement, EXHAUSTED), TrackerWrite.OWED)
                break
            case 'aborted':
                create(implement)
                repository.recordOutcome(TASK, new TaskOutcome.Aborted(implement, new AttemptKey(TASK, 'implement', 0),
                        UntrustedText.subprocess('fetch failed')), TrackerWrite.OWED)
                break
            default:
                throw new IllegalArgumentException(shape)
        }
    }

    /** The {@code status --json} document the command printed for the task, parsed. */
    private Map statusJson() {
        def console = new ScriptedConsoleIO()
        new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(registeredClone), console)
                .run(new DefaultApplicationArguments('status', '--dir=' + cloneDir, TASK, '--json'))
        new ObjectMapper().readValue(console.printedMachine.first(), Map)
    }

    // FR7, FR10 of make-checkpoint-gate-durable: the continuation consumed the park in its own
    //     commit, and the process died before any round — the park is gone from the report.
    // Scenario: observability/status-report / A consumed park is not shown
    def "a park consumed by #consumer then left by a dead process shows no outcome and the continuation's position"() {
        given:
        tip(consumer)

        when:
        def report = statusJson()

        then:
        report.containsKey('outcome')
        report.outcome == null
        report.position == [type: 'atStage', stage: stage]

        where:
        consumer || stage
        'approval' || 'verify'
        'resumed write' || 'implement'
    }

    // FR11 of add-manual-run; FR7 of make-checkpoint-gate-durable: outcome is null exactly when no
    //     recorded outcome stands at the tip — mid-run, or after any of the three consumers.
    // Scenario: observability/status-report / The outcome is null mid-run and after a consumed park
    def "the outcome of a #shape tip is #expected"() {
        given:
        tip(shape)

        when:
        def outcome = statusJson().outcome

        then:
        (outcome == null ? null : outcome.type) == expected

        where:
        shape || expected
        'mid-run' || null
        'decision' || null
        'approval' || null
        'resumed write' || null
        'completed' || 'completed'
        'paused' || 'paused'
        'escalated' || 'escalated'
        'aborted' || 'aborted'
    }
}
