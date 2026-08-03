package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.lease.VirtualMonotonicTime
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The M2 death-and-recovery proof for the {@code take} run (task 6.6 of add-claim-heartbeat, FR4,
 * G1, M2), driven by {@link TakeCommand} against a REAL tracker adapter and a real local git repo:
 * a task X held by a DEAD instance A returns to circulation automatically — reaped by ANOTHER
 * instance's ordinary run — and is later resumed from A's branch, with no human in the loop.
 *
 * <p>The scenario, all deterministic — a controlled reaper sleeper ({@link BlockingSleeper}, separate
 * from B's beat sleeper per fix-reaper-idle-liveness FR5) steps B's STANDING reaper one tick at a
 * time, independent of B's beat thread, and a controlled {@link VirtualMonotonicTime} moves X's
 * claim past its TTL between two observations, so no real time passes:
 *
 * <ol>
 *   <li>Instance A delivers X for real, leaving a {@code Completed} task branch; then A "dies"
 *       holding X — modelled by the finish tracker-write being lost, so X still shows {@code
 *       Working(instance-a)} with a claim that is now never beaten again ({@link #deadenClaim}).
 *   <li>Instance B runs its OWN take of a separate Ready task Y. B's standing reaper ticks
 *       independently of B's beat thread; across two reap ticks spanning more than the TTL, X's
 *       unchanged claim is judged stale and {@code removeStaleClaim}d — X returns to {@code Ready}
 *       with the {@code stale-claim-removed} marker naming instance-a, and B never claims X for
 *       itself (FR4, D5; fix-reaper-idle-liveness FR5).
 *   <li>A later take of X claims it by ordinary lease and reconciles from A's branch — the recorded
 *       delivery drives a zero-engine-round finish, not a fresh start from the pipeline's first stage.
 * </ol>
 *
 * <p>Abstract for the same reason as {@link TakeHeartbeatLifecycleSpecBase}: a concrete adapter's
 * seeding, thread-reading, and claim-deadening name the concrete adapter type, so they live in a
 * subclass inside {@code adapter.tracker} while this base — which constructs the package-private
 * {@link TakeCommand}/{@link ManualRunAssembly} — stays in {@code app} and touches the tracker only
 * through the {@link Tracker} port. {@link #seededReadyTrackerAndFactory}, {@link #deadenClaim}, and
 * {@link #thread} are the three seams a subclass fills in.
 *
 * <p>Implements FR4, G1, M2 of add-claim-heartbeat.
 */
abstract class TakeDeathAndRecoverySpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    protected static final TaskRef X = new TaskRef('PROJ-1')
    protected static final TaskRef Y = new TaskRef('PROJ-2')

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker
    TrackerAdapterFactory trackerFactory
    BlockingSleeper sleeper = new BlockingSleeper()
    // B's standing reaper gets its OWN sleeper (fix-reaper-idle-liveness FR5), independent of the
    // beat's: this spec steps the reaper's ticks directly and never drives the beat sleeper.
    BlockingSleeper reaperSleeper = new BlockingSleeper()
    VirtualMonotonicTime monotonic = new VirtualMonotonicTime()

    /** @return {@code [Tracker, TrackerAdapterFactory]} for two fresh Ready tasks seeded at {@link #X} and {@link #Y} */
    abstract List seededReadyTrackerAndFactory()

    /** Models a dead holder: force {@code ref} to {@code Working(holder)} with a claim that is never beaten again. */
    abstract void deadenClaim(TaskRef ref, String holder)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    def setup() {
        def seeded = seededReadyTrackerAndFactory()
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory

        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private FactoryProperties props(String instanceName, String scenario) {
        testProperties(instanceName: instanceName, agentCliBinary: FakeAgentSupport.propertiesFor(scenario).agentCliBinary())
    }

    /** A production {@link TakeCommand} (real beat sleeper + monotonic time): its beat thread just parks for real. */
    private TakeCommand productionCommand(FactoryProperties factoryProperties) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithub())
    }

    /** Instance B's {@link TakeCommand}: the standing reaper's own sleeper and its monotonic clock
     * are both controllable, independent of B's beat sleeper (fix-reaper-idle-liveness FR5). */
    private TakeCommand steppableCommand(FactoryProperties factoryProperties) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithub(),
                TakeCommandSeams.DEFAULTS
                .withHeartbeatSleeper(sleeper)
                .withReaperSleeper(reaperSleeper)
                .withHeartbeatMonotonicTime(monotonic)
                .withTakeoverConfirmation(TakeoverConfirmation.UNAVAILABLE))
    }

    private static int runExitCode(TakeCommand command, DefaultApplicationArguments appArgs) {
        try {
            command.run(appArgs)
            throw new IllegalStateException('take did not exit with a TakeExitCodeException')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    def "M2: a dead instance's Working claim is reaped by another run and later resumed from its branch"() {
        given: 'instance A delivers X for real, leaving a Completed task branch'
        assert runExitCode(productionCommand(props('instance-a', 'plain-round')), args('take', 'PROJ-1', "--dir=$projectDir")) == 0
        assert tracker.fetchTask(X).state() instanceof TrackerTaskState.Finished

        and: 'A then "dies" holding X: the finish write is lost, X still shows Working(instance-a) with a claim never beaten again'
        deadenClaim(X, 'instance-a')

        when: 'instance B runs its OWN take of Y on another thread so the test can step B\'s standing reaper mid-round'
        def commandB = steppableCommand(props('instance-b', 'plain-round-slow'))
        def executor = Executors.newSingleThreadExecutor()
        def appArgs = args('take', 'PROJ-2', "--dir=$projectDir")
        Future<?> runB = executor.submit({ commandB.run(appArgs) } as Callable)

        and: 'B\'s standing reaper — independent of B\'s beat thread — observes X\'s frozen claim twice, the monotonic clock advancing past the TTL between observations'
        assert reaperSleeper.awaitEntered(10000) != null: 'B\'s standing reaper never entered its first interval sleep'
        reaperSleeper.releaseOne() // reap tick 1: reaper first-sees X (fresh grace window)
        assert reaperSleeper.awaitEntered(10000) != null: 'B\'s standing reaper stopped before the second reap tick'
        monotonic.advance(Duration.ofHours(1)) // > TTL (default 5 min x 3)
        reaperSleeper.releaseOne() // reap tick 2: X\'s unchanged claim is now stale -> removeStaleClaim
        assert reaperSleeper.awaitEntered(10000) != null: 'B\'s standing reaper stopped before parking after the second reap tick'

        then: 'X is back to Ready, carrying the stale-claim-removed marker naming instance-a, and was NOT claimed by B'
        tracker.fetchTask(X).state() instanceof TrackerTaskState.Ready
        def reapedThread = thread(tracker, X)
        reapedThread.any { it.startsWith('STALE_CLAIM_REMOVED') && it.contains('instance-a') }
        reapedThread.every { !it.contains('instance-b') }

        when: 'B\'s own round completes'
        Throwable thrownB = null
        try {
            runB.get(30, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrownB = e.cause
        }

        then: 'B delivered its OWN task Y (exit 0), Y ended Finished — B\'s result is about Y, never X'
        thrownB instanceof TakeExitCodeException
        (thrownB as TakeExitCodeException).exitCode() == 0
        tracker.fetchTask(Y).state() instanceof TrackerTaskState.Finished

        when: 'a later, fresh instance takes X — now Ready again — by ordinary lease'
        def exitX = runExitCode(productionCommand(props('instance-c', 'plain-round')), args('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'it claims X and reconciles from A\'s branch: the recorded delivery drives a zero-round finish, not a fresh start'
        exitX == 0
        tracker.fetchTask(X).state() instanceof TrackerTaskState.Finished
        def resumeThread = thread(tracker, X)
        resumeThread.size() == 3
        resumeThread[0].startsWith('STALE_CLAIM_REMOVED')
        resumeThread[1].startsWith('CLAIM')
        resumeThread[1].contains('instance-c')
        resumeThread[2].startsWith('FINISH')
        resumeThread.every { !it.startsWith('PROGRESS') }

        cleanup:
        executor.shutdownNow()
    }
}
