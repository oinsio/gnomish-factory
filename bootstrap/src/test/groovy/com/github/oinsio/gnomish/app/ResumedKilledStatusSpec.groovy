package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Success metric M4 of make-checkpoint-gate-durable (FR7, FR12, design D4): a {@code status --json}
 * document for a resumed-then-killed task shows {@code outcome: null}.
 *
 * <p>The whole flow is the production one on a real bare origin ({@link HostContinuationMedium}): a
 * {@code gnomish run} exhausts its one attempt and parks on an {@code AttemptsExhausted} escalation;
 * {@code gnomish run --resume} without a decision lands the resumed write ({@code
 * TaskRepository.resumeFrom}, reached through {@code EscalationResume}) and the process dies right
 * after it, before any round; {@code gnomish status --json} — {@link StatusCommand} over the branch,
 * rendered by {@code StatusReportJsonMapper} — then reads the tip the kill froze.
 */
class ResumedKilledStatusSpec extends Specification implements StdoutCaptureFixture {

    private static final String TASK = 'M4-1'

    @TempDir
    Path tempDir

    // M4, FR7, FR12: the resumed-then-killed tip reads as a consumed park — outcome null, attempts reset
    def "M4: status --json of a resumed-then-killed task shows outcome null and the reset attempts"() {
        given: 'a task parked by an exhausted attempt limit, on origin'
        def medium = new HostContinuationMedium(tempDir)
        def definition = ParkPipelines.escalating()
        medium.park(TASK, definition, 'plain-round')
        def history = new BranchHistory(medium.origin(), TASK)
        String park = history.tip()

        expect: 'the park recorded an outcome and spent the attempt'
        history.outcome(park) != null
        history.state(park).attemptsUsed() == 1

        when: 'run --resume without a decision dies right after its resumed commit'
        medium.resume(TASK, definition, 'plain-round', null, killedAfterResumedWrite(TaskGitFixture.real()))

        then: 'the kill came after the resumed write, and that write is the tip on origin'
        thrown(RunKills.SimulatedKill)
        history.parent(history.tip()) == park
        history.subject(history.tip()) == 'gnomish: task resumed'

        when: 'the operator asks for the task\'s status as JSON'
        def args = new DefaultApplicationArguments('status', '--dir=' + medium.registeredClone().clonePath(), TASK, '--json')
        def output = captureStdout {
            new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(medium.registeredClone()),
            liveConsole()).run(args)
        }
        def report = new ObjectMapper().readValue(output.substring(0, output.lastIndexOf('Worktree:')), Map)

        then: 'M4: the consumed park shows outcome null'
        report.containsKey('outcome')
        report.outcome == null

        and: 'the position is kept and the attempts the return granted are reset'
        report.position == [type: 'atStage', stage: 'build']
        report.currentStage.attemptsUsed == 0
        report.currentStage.attempts == []

        and: 'the escalation that was consumed stays visible as history'
        report.lastEscalation != null
    }

    /** {@code git} whose lifecycle store dies right after a resumed write lands; nothing else changes. */
    private static TaskGit killedAfterResumedWrite(TaskGit git) {
        new TaskGit(new KillAfterResumeStoreGit(git.store()), git.branches(), git.worktrees(), git.midRoundPush(),
                git.baseRefs(), git.epochs())
    }

    private static final class KillAfterResumeStoreGit implements TaskStoreGit {
        @Delegate
        private final TaskStoreGit inner

        KillAfterResumeStoreGit(TaskStoreGit inner) {
            this.inner = inner
        }

        @Override
        TaskLifecycleStore taskRepository(RegisteredClone clone) {
            new KillAfterResumeStore(inner.taskRepository(clone))
        }
    }

    private static final class KillAfterResumeStore implements TaskLifecycleStore {
        @Delegate
        private final TaskLifecycleStore inner

        KillAfterResumeStore(TaskLifecycleStore inner) {
            this.inner = inner
        }

        @Override
        void resumeFrom(String taskId, TaskState reset) {
            inner.resumeFrom(taskId, reset)
            throw new RunKills.SimulatedKill('after the resumed commit, before any round')
        }
    }
}
