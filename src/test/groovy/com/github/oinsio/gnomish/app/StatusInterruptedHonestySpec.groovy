package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R2 of add-git-workflow: "a crash between the last round commit and the outcome write yields
 * 'rounds present, no outcome' — {@code status} reports it honestly as in-progress/interrupted
 * (matches nullable live fields of contract v1)". This spec simulates exactly that crash — a task
 * branch with a recorded round commit but no {@code TaskRepository#recordOutcome} call ever made —
 * and asserts {@code gnomish status} (task-inspection, FR13) reports it honestly in both text and
 * {@code --json} rendering: the round is visible, and nothing in the output claims completion or
 * hides the unresolved outcome. Real git repos throughout, matching {@code
 * BranchStateReaderSpec}/{@code StatusCommandSpec}'s adapter-layer convention — no stubbing of the
 * branch reader.
 */
class StatusInterruptedHonestySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private StatusCommand newCommand() {
        new StatusCommand(worktreesRoot)
    }

    /** Records exactly one round commit and, deliberately, never calls {@code recordOutcome} —
     * the branch state a process leaves behind if it dies right after the round commit. */
    private void recordInterruptedRound(String taskId, String stage = 'implement', int round = 0) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot)
                .createTask(new TaskContext(taskId, 'Fix the thing', 'Body', []), null)
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        def attempt = new AttemptRecord(round, AttemptRecord.Result.PASSED,
                Instant.parse('2026-07-18T09:00:00Z'), [], ExecutorUsage.none(), JudgeUsage.none())
        def state = TaskState.atStageStart(stage).recordUnburnedRound(attempt)
        persistence.persist(taskId, state, trace)
    }

    private static String captureStdout(Closure action) {
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.out = originalOut
        }
        return out.toString('UTF-8')
    }

    def "NFR-R2: text status of an interrupted task shows the recorded round and no completion claim"() {
        given: 'a round commit exists, but the process died before ever writing an outcome'
        recordInterruptedRound('PROJ-INT-1')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-INT-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'the recorded round is visible — nothing about the crash was lost'
        output.contains('Stage: implement')
        output.contains('Round 0')

        and: 'the report never claims the task finished — no outcome section is rendered'
        !output.toLowerCase().contains('completed')
        !output.toLowerCase().contains('paused')
        !output.toLowerCase().contains('escalated')
    }

    def "NFR-R2: --json status of an interrupted task carries a null outcome alongside the visible round"() {
        given:
        recordInterruptedRound('PROJ-INT-2')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-INT-2', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'contract v1: outcome is the nullable live field, honestly null while unresolved'
        output.contains('"outcome" : null')

        and: 'the round that did happen is still fully reported — honesty means visible, not hidden'
        output.contains('"round" : 0')
        output.contains('"attemptsUsed" : 0')
    }

    def "NFR-R2: a task with a recorded outcome is distinguishable from the interrupted case"() {
        given: 'contrast fixture — same shape, but recordOutcome was actually called (Paused, not Completed, so FR15 cleanup does not remove .gnomish-task/ before this read)'
        recordInterruptedRound('PROJ-INT-3')
        new GitTaskRepository(runner, cloneDir, worktreesRoot).recordOutcome('PROJ-INT-3',
                new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-INT-3', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'the paused task reports a non-null outcome — unlike the interrupted one above'
        !output.contains('"outcome" : null')
        output.contains('paused')
    }
}
