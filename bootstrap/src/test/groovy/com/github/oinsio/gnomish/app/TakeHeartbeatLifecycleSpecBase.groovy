package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The heartbeat-lifecycle proof for the {@code take} run (task 6.1 of add-claim-heartbeat, FR1):
 * driven by {@link TakeCommand} against a REAL tracker adapter and a real local git repo, it shows
 * that once a claim is acquired the instance beats it on the configured interval WHILE a long round
 * is in flight, and that beating STOPS at the terminal result. The beat interval sleeper is a
 * controllable {@link BlockingSleeper}, so the spec drives beats one tick at a time with no real
 * sleeping; the "long round" is the {@code plain-round-slow} fake-agent scenario, which stays in
 * flight for a couple of seconds so the claim is genuinely held while the beats land.
 *
 * <p>The standing reaper (fix-reaper-idle-liveness) gets its OWN {@link BlockingSleeper}, separate
 * from the beat's — this spec never drives it (never calls {@code awaitEntered}/{@code releaseOne}
 * on it), so the reaper thread parks on it forever, quietly, and can never steal a release meant for
 * the beat thread being driven below.
 *
 * <p>Abstract for the same reason as {@link TakeLifecycleReadyToDeliveredSpecBase}: a concrete
 * adapter's seeding and thread-reading name the concrete adapter type, so they live in a subclass
 * inside {@code adapter.tracker} while this base — which constructs the package-private {@link
 * TakeCommand}/{@link ManualRunAssembly} — stays in {@code app} and touches the tracker only
 * through the {@link Tracker} port. {@link #seededReadyTrackerAndFactory} and {@link #heartbeatCount}
 * are the two seams a subclass fills in.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
abstract class TakeHeartbeatLifecycleSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker
    TrackerAdapterFactory trackerFactory
    BlockingSleeper sleeper
    // The standing reaper's own sleeper (fix-reaper-idle-liveness FR5): never driven by this spec, so
    // the reaper thread parks on it forever and can never steal a release meant for the beat thread.
    BlockingSleeper reaperSleeper

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return how many heartbeat beats the tracker has recorded on {@code ref}'s claim so far */
    abstract int heartbeatCount(Tracker tracker, TaskRef ref)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        sleeper = new BlockingSleeper()
        reaperSleeper = new BlockingSleeper()

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

    private TakeCommand newCommand(FactoryProperties factoryProperties) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithubSource(),
                TakeCommandSeams.DEFAULTS
                .withHeartbeatSleeper(sleeper)
                .withReaperSleeper(reaperSleeper))
    }

    def "FR1: the held claim is beaten during a long round and beating stops at the terminal result"() {
        given: 'a Ready task and a fake-agent stage that stays in flight for a couple of seconds'
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round-slow')
        def command = newCommand(factoryProperties)
        // Built outside the closure below: the args helper is a private static method the closure
        // cannot resolve against the spec instance (Groovy dynamic dispatch), so capture it here.
        def appArgs = args('take', 'PROJ-1', "--dir=$projectDir")
        def executor = Executors.newSingleThreadExecutor()

        when: 'take runs the ready task on another thread so the test can drive beats mid-round'
        Future<?> run = executor.submit({ command.run(appArgs) } as Callable)

        and: 'the beat thread parks in its first interval sleep, then the test drives two beats'
        // Bounded waits (not the unbounded awaitEntered()) so a mutant that never starts the beat
        // thread — e.g. dropping heartbeat.register(ref) in TakeClaimAndWork#dispatchAfterClaim —
        // is KILLED by a fast assertion here rather than hanging this covering spec into a PIT
        // TIMED_OUT. The window is generous versus the near-instant real start; only a broken beat
        // lifecycle ever exhausts it.
        def firstInterval = sleeper.awaitEntered(10000)
        assert firstInterval != null: 'the beat thread never entered its first interval sleep'
        sleeper.releaseOne() // beat #1
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before beat #2'
        sleeper.releaseOne() // beat #2
        // the worker is now parked again, having beaten twice
        assert sleeper.awaitEntered(10000) != null: 'the beat thread stopped before parking after beat #2'

        then: 'the beat thread slept the configured default beat interval (FR1, config default)'
        firstInterval == TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL

        and: 'the held claim was beaten twice while the round was still in flight (FR1)'
        int beatsDuringRound = heartbeatCount(tracker, REF)
        beatsDuringRound == 2

        when: 'the round completes to its terminal result'
        Throwable thrown = null
        try {
            run.get(30, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrown = e.cause
        }

        then: 'the run reached the Delivered exit code (0), and the task ended Finished'
        thrown instanceof TakeExitCodeException
        (thrown as TakeExitCodeException).exitCode() == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        when: 'the parked beat thread is woken once more, now that the claim was unregistered'
        sleeper.releaseOne()

        then: 'it finds no held claim and stops without ever sleeping — or beating — again (FR1)'
        sleeper.awaitEntered(1000) == null
        heartbeatCount(tracker, REF) == beatsDuringRound

        cleanup:
        executor.shutdownNow()
    }
}
