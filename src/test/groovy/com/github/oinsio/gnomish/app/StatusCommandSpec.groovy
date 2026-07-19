package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
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
 * FR13, FR6 of add-git-workflow (task 5.3): {@code gnomish status --dir <clone> <task>} renders
 * text/JSON v1 from the branch state reader and shows the worktree path. Real git repos throughout
 * (matching {@code BranchStateReaderSpec}'s adapter-layer convention) — no stubbing of the reader.
 */
class StatusCommandSpec extends Specification implements BareGitRepoFixture {

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

    private void persistRound(String taskId, TaskState state, String stage = 'implement', int round = 0) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot).createTask(new TaskContext(taskId, 'Fix the thing', 'Body', []), null)
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
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

    def "FR13: text render of a found task prints the status block"() {
        given:
        persistRound('PROJ-1', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('Task: PROJ-1')
        output.contains('Fix the thing')
        output.contains('Stage: implement')
    }

    def "FR13: --json render of a found task prints the v1 JSON contract"() {
        given:
        persistRound('PROJ-2', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-2', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"version" : 1')
        output.contains('"id" : "PROJ-2"')
    }

    def "FR6: the worktree path is shown for a found task"() {
        given:
        persistRound('PROJ-3', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-3')
        def expectedWorktree = worktreesRoot.resolve('clone').resolve('PROJ-3')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('Worktree: ' + expectedWorktree)
    }

    def "task-inspection: interrupted task (outcome null) is rendered honestly as in-progress"() {
        given: 'a task with a recorded round but no recordOutcome call — a crash mid-flight'
        persistRound('PROJ-4', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-4', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then: 'contract v1 renders a null outcome field, matching the nullable live fields'
        output.contains('"outcome" : null')
    }

    def "FR13, UX3: a task branch absent everywhere prints a plain not-found message and throws TaskNotFoundException, no stack trace"() {
        given:
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'NO-SUCH-TASK')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) { newCommand().run(args) }

        then:
        output.contains('task not found')
        output.contains('NO-SUCH-TASK')
    }

    def "FR13, UX3, D15: 'Deleted branch after merge' — a branch that existed and was deleted reports not-found the same as a never-existing task"() {
        given: 'a task branch created, its worktree removed, then the branch deleted — mirroring a merged-and-cleaned-up PR'
        persistRound('PROJ-7', TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-7')
        runner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        runner.run(cloneDir, 'branch', '-D', 'gnomish/PROJ-7')
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, 'PROJ-7')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) { newCommand().run(args) }

        then:
        output.contains('task not found: PROJ-7')
    }

    def "FR13: status without a <task> argument lists all tasks as a text table"() {
        given:
        persistRound('PROJ-5', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir)

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('PROJ-5')
        output.contains('implement')
    }

    def "FR13: status without a <task> argument, --json lists all tasks as a JSON array"() {
        given:
        persistRound('PROJ-6', TaskState.atStageStart('implement'))
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir, '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"taskId" : "PROJ-6"')
    }

    def "FR13: status without a <task> argument prints 'no tasks found' when the clone has no gnomish/* branch"() {
        given:
        def args = new DefaultApplicationArguments('status', '--dir=' + cloneDir)

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        noExceptionThrown()
        output.contains('no tasks found')
    }

    def "UsageException: --dir is required"() {
        given:
        def args = new DefaultApplicationArguments('status', 'PROJ-1')

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }
}
