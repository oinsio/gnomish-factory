package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.domain.engine.TaskContext
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR14 of add-git-workflow: {@link SubcommandDispatch} routes {@code status}/{@code usage}
 * to their dedicated commands and reports back that the invocation was handled, leaving the
 * {@code run} subcommand (explicit or implicit) for {@link ManualRunRunner}'s own flow.
 *
 * <p>{@link StatusCommand}/{@link UsageCommand} are {@code final} (project convention) and this
 * codebase has no Mockito, so real instances are used and dispatch is proven by which command's
 * own observable behavior actually ran (a distinct exception/stdout each), rather than by mocking.
 */
class SubcommandDispatchSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path worktreesRoot

    def dispatch = new SubcommandDispatch(new StatusCommand(worktreesRoot), new UsageCommand())

    // FR13: 'status' actually reaches StatusCommand#run (PIT: VoidMethodCallMutator survivor) —
    // proven by its list-mode output, and reports the invocation as handled.
    def "dispatchNonRun() routes to StatusCommand for the 'status' subcommand and returns true"() {
        given:
        def args = new DefaultApplicationArguments('status', "--dir=${worktreesRoot}".toString())
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')

        when:
        def handled = dispatch.dispatchNonRun(args)

        then:
        handled
        captured.toString('UTF-8').contains('no tasks found')

        cleanup:
        System.out = originalOut
    }

    // FR14: 'usage' actually reaches UsageCommand#run — proven by its distinct failure mode
    // (TaskNotFoundException, never thrown by StatusCommand's list mode) — and reports the
    // invocation as handled.
    def "dispatchNonRun() routes to UsageCommand for the 'usage' subcommand and returns true"() {
        given:
        def args = new DefaultApplicationArguments('usage', "--dir=${worktreesRoot}".toString(), 'task-1')

        when:
        dispatch.dispatchNonRun(args)

        then: 'the exception itself proves UsageCommand#run — not StatusCommand#run, not a silent no-op — ran'
        thrown(TaskNotFoundException)
    }

    // FR14, PIT NO_COVERAGE: the previous scenario's usage command throws before ever reaching
    // dispatchNonRun's own `return true`, leaving that line unexercised — this scenario drives a
    // 'usage' invocation that finds a real task branch and completes normally, so execution
    // actually reaches and returns `true`.
    def "dispatchNonRun() returns true for a 'usage' subcommand that completes without error"() {
        given: 'a real git clone carrying one task branch, so UsageCommand#run finds it and returns normally'
        def cloneDir = initWorkingRepo(worktreesRoot, 'clone')
        def runner = new GitProcessRunner()
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        new GitTaskRepository(runner, cloneDir, worktreesRoot.resolve('worktrees'))
                .createTask(new TaskContext('PROJ-1', 'T', 'B', []), null)

        def args = new DefaultApplicationArguments('usage', "--dir=${cloneDir}".toString(), 'PROJ-1')
        def originalOut = System.out
        System.out = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')

        when:
        def handled = dispatch.dispatchNonRun(args)

        then:
        noExceptionThrown()
        handled

        cleanup:
        System.out = originalOut
    }

    // FR13, FR14: the 'run' subcommand (explicit or implicit) invokes neither command and reports
    // the invocation as NOT handled — the caller must still drive the run flow itself (PIT:
    // BooleanFalseReturnValsMutator survivor on this exact `return false`).
    def "dispatchNonRun() invokes neither command and returns false for the 'run' subcommand"() {
        given:
        def args = new DefaultApplicationArguments(sourceArgs as String[])
        def originalOut = System.out
        System.out = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')

        when:
        def handled = dispatch.dispatchNonRun(args)

        then:
        noExceptionThrown()
        !handled

        cleanup:
        System.out = originalOut

        where:
        sourceArgs << [
            ['--dir=.', '--task=x'],
            ['run', '--dir=.', '--task=x']
        ]
    }
}
