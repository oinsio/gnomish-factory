package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR14 of add-git-workflow; FR9 of add-tracker-port (task 5.13); FR2 of add-factory-serve
 * (task 5.1): {@link SubcommandDispatch} routes {@code status}/{@code usage}/{@code take}/{@code
 * serve} to their dedicated commands and reports back that the invocation was handled, leaving
 * the {@code run} subcommand (explicit or implicit) for {@link ManualRunRunner}'s own flow.
 *
 * <p>{@link StatusCommand}/{@link UsageCommand}/{@link TakeCommand}/{@link ServeCommand} are
 * {@code final} (project convention) and this codebase has no Mockito, so real instances are used
 * and dispatch is proven by which command's own observable behavior actually ran (a distinct
 * exception/stdout each), rather than by mocking.
 */
class SubcommandDispatchSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path worktreesRoot

    @TempDir
    Path homeDir

    private TakeCommand newTakeCommand() {
        TakeCommandFactory.of(
                newAssembly(new ByteArrayInputStream(new byte[0])), TaskGitFixture.real(), worktreesRoot, 'taskId',
                testProperties(), Clock.systemUTC(), [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }

    private ServeCommand newServeCommand() {
        new ServeCommand(
                newAssembly(new ByteArrayInputStream(new byte[0])), TaskGitFixture.real(), worktreesRoot, homeDir, 'taskId',
                testProperties(), new ServeProperties(0, null, null, null, null, null, null), Clock.systemUTC(),
                new SystemClock(), [:], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource(),
                { FeedAutomaton automaton -> } as FeedAutomatonStarter, SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())
    }

    private BoardCommand newBoardCommand() {
        new BoardCommand(Clock.systemUTC(), testProperties(), [:], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource())
    }

    private DashboardCommand newDashboardCommand() {
        new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), homeDir, testProperties(), [:],
        MapSecretsProvider.NONE,
        TrackerValidatorStub.acceptingGithubSource())
    }

    def dispatch = new SubcommandDispatch(
    new StatusCommand(TaskGitFixture.real(), worktreesRoot), new UsageCommand(TaskGitFixture.real()), newTakeCommand(), newServeCommand(),
    newBoardCommand(), newDashboardCommand())

    // FR13: 'status' actually reaches StatusCommand#run (PIT: VoidMethodCallMutator survivor) —
    // proven by its list-mode output, and reports the invocation as handled.
    def "dispatchNonRun() routes to StatusCommand for the 'status' subcommand and returns true"() {
        // A real (if empty) clone: since FR13 of harden-logging-observability a ref enumeration
        // git refuses fails the listing instead of rendering as a verified "no tasks", so the
        // dispatch target needs a directory git will actually enumerate.
        given: 'a clone with no task branches in it'
        assert gitExitCode(worktreesRoot, 'init') == 0
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
        commitAll(cloneDir)
        new GitTaskRepository(runner, cloneDir, worktreesRoot.resolve('worktrees'), ClaimEpochSource.NONE)
                .createTask(new TaskContext('PROJ-1', 'T', 'B', []), null, TaskState.atStageStart('build'))

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

    // FR9 of add-tracker-port: 'take' reaches TakeCommand#run and reports the invocation as
    // handled — proven by TakeCommand's own distinct failure mode (a project with no .gnomish/
    // at all fails pipeline load, never StatusCommand's/UsageCommand's own error shapes).
    def "dispatchNonRun() routes to TakeCommand for the 'take' subcommand and returns true"() {
        given:
        def args = new DefaultApplicationArguments('take', "--dir=${worktreesRoot}".toString())

        when:
        dispatch.dispatchNonRun(args)

        then: 'no .gnomish/ tree under worktreesRoot: pipeline load fails, proving TakeCommand#run ran'
        thrown(IOException)
    }

    // `expandRef` is intentionally unimplemented (never called by this fixture's `serve` path):
    // Groovy's map-to-interface coercion throws UnsupportedOperationException if it ever were.
    private static TrackerAdapterFactory factoryReturning(Tracker t) {
        [
            type: { 'github' },
            create: { SecretsProvider secrets, TrackerConfig config, String instanceId ->
                t
            },
        ] as TrackerAdapterFactory
    }

    /** A minimal, valid `.gnomish/` tree with a `tracker: github` section, under {@code root}. */
    private static void writeMinimalPipeline(Path root) {
        Files.createDirectories(root.resolve('.gnomish/stages/build'))
        Files.writeString(root.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(root.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(root.resolve('.gnomish/stages/build/stage.yaml'),
                'purpose: build it\nexecutor:\n  type: agent-cli\n  model: model-x\n' +
                'instructions: stages/build/instructions.md\nadvancement: auto\n')
        Files.writeString(root.resolve('.gnomish/config.yaml'),
                'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n' +
                '  github:\n    api-url: https://api.github.com\n    repo: acme/widgets\n')
    }

    // FR2 of add-factory-serve (task 5.1), PIT NO_COVERAGE: 'serve' reaches ServeCommand#run
    // (VoidMethodCallMutator survivor); a reachable tracker binding plus the non-blocking
    // FeedAutomatonStarter test seam (documented on ServeCommand#run) lets the invocation
    // complete normally, exercising dispatchNonRun's `return true` for SERVE (BooleanFalseReturnValsMutator survivor).
    def "dispatchNonRun() routes to ServeCommand for the 'serve' subcommand and returns true"() {
        given: 'a serve-only dispatch, wired with a reachable tracker factory and a starter that records invocation'
        writeMinimalPipeline(worktreesRoot)
        def starterInvoked = new AtomicBoolean(false)
        def serveDispatch = new SubcommandDispatch(
                dispatch.statusCommand(), dispatch.usageCommand(), dispatch.takeCommand(),
                new ServeCommand(
                        newAssembly(new ByteArrayInputStream(new byte[0])), TaskGitFixture.real(), worktreesRoot, homeDir, 'taskId',
                        testProperties(), new ServeProperties(0, null, null, null, null, null, null), Clock.systemUTC(),
                        new SystemClock(), [github: factoryReturning(Stub(Tracker))],
                        MapSecretsProvider.NONE,
                        TrackerValidatorStub.acceptingGithubSource(), { FeedAutomaton automaton ->
                            starterInvoked.set(true)
                        } as FeedAutomatonStarter, SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                        new ClaimEpochBook()),
                dispatch.boardCommand(), dispatch.dashboardCommand())
        def args = new DefaultApplicationArguments('serve', "--dir=${worktreesRoot}".toString())

        when:
        def handled = serveDispatch.dispatchNonRun(args)

        then: 'ServeCommand.run() genuinely executed through to starting the feed automaton'
        noExceptionThrown()
        starterInvoked.get()
        handled
    }

    // FR1 of add-board-command (design D1), task 3.1/3.2: 'board' reaches BoardCommand#run —
    // proven by its own distinct failure mode (worktreesRoot has no .gnomish/, so pipeline load
    // fails with an IOException, distinct from every other subcommand's failure shape here); the
    // real board behavior itself is BoardCommandSpec's job, not this routing spec's.
    def "dispatchNonRun() routes to BoardCommand for the 'board' subcommand"() {
        given:
        def args = new DefaultApplicationArguments('board', "--dir=${worktreesRoot}".toString())

        when:
        dispatch.dispatchNonRun(args)

        then:
        thrown(IOException)
    }

    // FR1 of add-board-command (design D1), PIT NO_COVERAGE + BooleanFalseReturnVals: the routing
    // scenario above throws before ever reaching dispatchNonRun's own `return true` for BOARD. This
    // scenario drives a 'board' invocation that finds a real .gnomish/ tree and a reachable
    // read-only tracker, so BoardCommand#run completes normally and execution actually reaches and
    // returns `true`.
    def "dispatchNonRun() returns true for a 'board' subcommand that completes without error"() {
        given: 'a board-only dispatch with a valid pipeline and a tracker returning empty listings'
        writeMinimalPipeline(worktreesRoot)
        def boardDispatch = new SubcommandDispatch(
                dispatch.statusCommand(), dispatch.usageCommand(), dispatch.takeCommand(),
                dispatch.serveCommand(),
                new BoardCommand(Clock.systemUTC(), testProperties(),
                [github: factoryReturning(Stub(Tracker))], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource()),
                dispatch.dashboardCommand())
        def args = new DefaultApplicationArguments('board', "--dir=${worktreesRoot}".toString())
        def originalOut = System.out
        System.out = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')

        when:
        def handled = boardDispatch.dispatchNonRun(args)

        then:
        noExceptionThrown()
        handled

        cleanup:
        System.out = originalOut
    }

    // FR1 of add-dashboard-page (design D8): 'dashboard' reaches DashboardCommand#run — proven by
    // its own distinct failure mode (worktreesRoot has no .gnomish/, so pipeline load fails with
    // an IOException, the same shape 'board' fails with but via a genuinely distinct call path).
    def "dispatchNonRun() routes to DashboardCommand for the 'dashboard' subcommand"() {
        given:
        def args = new DefaultApplicationArguments('dashboard', "--dir=${worktreesRoot}".toString())

        when:
        dispatch.dispatchNonRun(args)

        then:
        thrown(IOException)
    }

    // FR1 of add-dashboard-page, PIT NO_COVERAGE + BooleanFalseReturnVals: the routing scenario
    // above throws before ever reaching dispatchNonRun's own `return true` for DASHBOARD. This
    // scenario drives a 'dashboard' invocation that finds a real .gnomish/ tree and a reachable
    // read-only tracker, so DashboardCommand#run completes normally (a one-shot render) and
    // execution actually reaches and returns `true`.
    def "dispatchNonRun() returns true for a 'dashboard' subcommand that completes without error"() {
        given: 'a dashboard-only dispatch with a valid pipeline and a tracker returning empty listings'
        writeMinimalPipeline(worktreesRoot)
        def dashboardDispatch = new SubcommandDispatch(
                dispatch.statusCommand(), dispatch.usageCommand(), dispatch.takeCommand(),
                dispatch.serveCommand(), dispatch.boardCommand(),
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), homeDir, testProperties(),
                [github: factoryReturning(Stub(Tracker))], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource()))
        def args = new DefaultApplicationArguments('dashboard', "--dir=${worktreesRoot}".toString())

        when:
        def handled = dashboardDispatch.dispatchNonRun(args)

        then:
        noExceptionThrown()
        handled
    }
}
