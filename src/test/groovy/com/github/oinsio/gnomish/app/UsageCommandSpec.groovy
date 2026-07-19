package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow (task 5.6): {@code gnomish usage --dir <clone> <task>
 * [--json]} wires {@link UsageArgumentsParser}, {@link
 * com.github.oinsio.gnomish.adapter.git.UsageHistoryWalker}, {@link UsageTextRenderer}, and {@link
 * com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper} together end to end against real
 * git fixtures (matching {@code StatusCommandSpec}'s adapter-layer convention).
 */
class UsageCommandSpec extends Specification implements BareGitRepoFixture {

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

    private UsageCommand newCommand() {
        new UsageCommand()
    }

    private static AttemptRecord round(int round, AttemptRecord.Result result, long wallMillis, long inputTokens) {
        new AttemptRecord(
                round,
                result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(round * 60),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(wallMillis), [], ['claude-x': new TokenUsage(inputTokens, 10, 0, 0)]),
                JudgeUsage.none())
    }

    private void persistRound(String taskId, TaskState state, String stage, int round) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot).createTask(new TaskContext(taskId, 'T', 'B', []), null)
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
        ])
        new GitAttemptPersistence(runner, worktree, taskId).persist(taskId, state, trace)
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

    /**
     * Like {@link #captureStdout}, but for actions expected to throw: runs {@code action} with
     * stdout captured, swallows exactly {@code thrownType} (asserting it was thrown) and returns
     * whatever reached stdout before the throw, so callers can assert on both in one block.
     */
    private static String captureStdoutExpectingThrow(Class<? extends Throwable> thrownType, Closure action) {
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        try {
            action.call()
            throw new AssertionError("expected ${thrownType.simpleName} to be thrown, but action completed normally")
        } catch (AssertionError rethrow) {
            throw rethrow
        } catch (Throwable t) {
            if (!thrownType.isInstance(t)) {
                throw t
            }
        } finally {
            System.out = originalOut
        }
        return out.toString('UTF-8')
    }

    def "FR14: text render prints the stage/round table and a totals line"() {
        given:
        def passed = round(0, AttemptRecord.Result.PASSED, 1000, 100)
        persistRound('PROJ-1', TaskState.atStageStart('implement').recordUnburnedRound(passed), 'implement', 0)
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('implement')
        output.contains('passed')
        output.contains('TOTAL')
    }

    def "FR14, NFR-C1: --json render prints the own version:1 mini-contract with full granularity"() {
        given:
        def passed = round(0, AttemptRecord.Result.PASSED, 1000, 100)
        persistRound('PROJ-2', TaskState.atStageStart('implement').recordUnburnedRound(passed), 'implement', 0)
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-2', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"version" : 1')
        output.contains('"taskId" : "PROJ-2"')
        output.contains('"claude-x"')
    }

    def "FR13, UX3: a task branch absent everywhere prints a plain not-found message and throws TaskNotFoundException, no stack trace"() {
        given:
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'NO-SUCH-TASK')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) { newCommand().run(args) }

        then:
        output.contains('task not found')
        output.contains('NO-SUCH-TASK')
    }

    def "FR13, UX3, D15: 'Deleted branch after merge' — a branch that existed and was deleted reports not-found the same as a never-existing task"() {
        given: 'a task branch created, its worktree removed, then the branch deleted — mirroring a merged-and-cleaned-up PR'
        persistRound('PROJ-3', TaskState.atStageStart('implement').recordUnburnedRound(round(0, AttemptRecord.Result.PASSED, 500, 10)), 'implement', 0)
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-3')
        runner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        runner.run(cloneDir, 'branch', '-D', 'gnomish/PROJ-3')
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-3')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) { newCommand().run(args) }

        then:
        output.contains('task not found: PROJ-3')
    }

    def "UsageException: --dir is required"() {
        given:
        def args = new DefaultApplicationArguments('usage', 'PROJ-1')

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }

    def "FR14: UsageException: a task id is required"() {
        given:
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir)

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }
}
