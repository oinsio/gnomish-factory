package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
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
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * Task 4.2 of fix-reaper-idle-liveness: the wiring proof that a real {@code take} run (through
 * {@link TakeCommand}, exactly like {@link TakeDeathAndRecoverySpecBase} and {@link
 * TakeHeartbeatLifecycleSpecBase}) starts and stops the standing reaper, and that reaping is no
 * longer coupled to the instance heartbeat's own thread.
 *
 * <p>Driven against a REAL {@link InMemoryTracker} on real virtual threads, with a short but REAL
 * beat interval (100ms) — deliberately NOT a {@link com.github.oinsio.gnomish.app.lease.BlockingSleeper}
 * rendezvous: {@link TakeHeartbeat#forRun} now wires {@code InstanceHeartbeat} and {@code
 * StandingReaper} onto the SAME injected sleeper as two INDEPENDENT loops (design D2), so a
 * single-step rendezvous could no longer tell "the beat thread's sleep" from "the reaper's sleep"
 * apart — {@code TakeHeartbeatLifecycleSpecBase}, which assumed sole ownership of that sleeper,
 * was flaky until it was given its own, never-driven reaper sleeper. This spec sidesteps that
 * ambiguity entirely by asserting on OBSERVABLE tracker state via {@link PollingConditions}
 * (bounded, no sleep-and-hope for anything this spec expects TO happen), the same idiom already
 * used for real-thread proofs elsewhere (e.g. {@code ServeCommandSpec}, {@code FeedAutomatonSpec}).
 *
 * <p>Covers tracker-take's "Take runs the heartbeat thread and the reaper duty" as modified (FR1,
 * FR5): the standing reaper starts independent of any claim and is stopped exactly once the run
 * returns. The beat-only heartbeat tick itself (never running the reaper duty) is already pinned
 * by {@code InstanceHeartbeatSpec}/{@code InstanceHeartbeatLifecycleSpec} (tasks 2.1-2.3) and is
 * not re-proven here. Also covers the "Reaping outlives the beat thread" scenario (FR2): the
 * SAME standing reaper, started once for the whole invocation, keeps reaping a foreign stale claim
 * across a batch even after the first task's own claim — and its beat registration — is over.
 *
 * <p>Implements FR1, FR2, FR5 of fix-reaper-idle-liveness.
 */
@Timeout(30)
class TakeCommandStandingReaperWiringSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    InMemoryTracker tracker
    InMemoryTrackerHarness harness

    def setup() {
        tracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(tracker)

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
        // A short, REAL beat interval and TTL (300ms = 100ms x 3): fast enough to observe real
        // reaping within seconds without any controllable sleeper (NFR-S1: still sourced only
        // from this factory clone's own config).
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  heartbeat-interval: 100ms
  heartbeat-ttl-multiplier: 3
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private static TrackerAdapterFactory fixedFactory(Tracker t) {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        t
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture: refs are already canonical')
                    }
                }
    }

    // plain-round-slow (2s in flight) so this spec has a real window to observe reaping while a
    // claim is genuinely held, without racing the round's own completion.
    private FactoryProperties testProps() {
        testProperties(agentCliBinary: FakeAgentSupport.propertiesFor('plain-round-slow').agentCliBinary())
    }

    private TakeCommand newCommand(ServeProperties serveProperties) {
        TakeCommandFactory.of(
                newAssembly(testProps()),
                worktreesRoot,
                'taskId',
                testProps(),
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: fixedFactory(tracker)],
                TrackerValidatorStub.acceptingGithub(),
                TakeCommandSeams.DEFAULTS.withServeProperties(serveProperties))
    }

    def "FR1, FR5: take starts the standing reaper independent of the claim, and stops it once the run returns"() {
        given: 'a Ready task to take, and an unrelated foreign stale claim'
        def x = new TaskRef('PROJ-1')
        harness.seed(x, new TaskSnapshot(x.id(), 'Add widgets', 'please add widgets'), new TrackerTaskState.Ready(), AbortFacts.none())
        def z = new TaskRef('PROJ-Z')
        harness.seedWorkingWithClaim(tracker, z, 'other-instance')
        def executor = Executors.newSingleThreadExecutor()
        def command = newCommand(new ServeProperties(1, null, null, null))

        when: 'take runs X on another thread, in flight for ~2s'
        Future<?> run = executor.submit({ command.run(args('take', x.id(), "--dir=$projectDir")) } as Callable)

        then: 'the standing reaper — started before X is even claimed — reaps the foreign claim WHILE the run is still in flight'
        new PollingConditions(timeout: 3, initialDelay: 0, delay: 0.05).eventually {
            tracker.fetchTask(z).state() instanceof TrackerTaskState.Ready
        }
        !run.done

        when: 'the run completes to its terminal result'
        Throwable thrown = null
        try {
            run.get(10, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrown = e.cause
        }

        then: 'X delivered'
        thrown instanceof TakeExitCodeException
        (thrown as TakeExitCodeException).exitCode() == 0
        tracker.fetchTask(x).state() instanceof TrackerTaskState.Finished

        when: 'a fresh foreign stale claim appears only after take has already returned'
        def w = new TaskRef('PROJ-W')
        harness.seedWorkingWithClaim(tracker, w, 'other-instance')

        then: 'it is never reaped — the standing reaper was stopped exactly when the run returned'
        Thread.sleep(900) // several multiples of the 100ms interval / 300ms TTL: generous margin
        tracker.fetchTask(w).state() instanceof TrackerTaskState.Working

        cleanup:
        executor.shutdownNow()
    }

    def "FR1, FR2: reaping outlives the first task's own claim/beat registration across a batch"() {
        given: 'two Ready tasks, run sequentially (one slot)'
        def x1 = new TaskRef('PROJ-1')
        def x2 = new TaskRef('PROJ-2')
        harness.seed(x1, new TaskSnapshot(x1.id(), 'Add widgets', 'please'), new TrackerTaskState.Ready(), AbortFacts.none())
        harness.seed(x2, new TaskSnapshot(x2.id(), 'Add gadgets', 'please'), new TrackerTaskState.Ready(), AbortFacts.none())
        def executor = Executors.newSingleThreadExecutor()
        def command = newCommand(new ServeProperties(1, null, null, null))

        when: 'the batch runs both refs sequentially, on another thread'
        Future<?> run = executor.submit({ command.run(args('take', x1.id(), x2.id(), "--dir=$projectDir")) } as Callable)

        and: 'the first task finishes — its own claim and beat registration are fully over'
        new PollingConditions(timeout: 5).eventually {
            tracker.fetchTask(x1).state() instanceof TrackerTaskState.Finished
        }

        and: 'only NOW does a foreign stale claim appear — it cannot have been reaped before this point'
        def z = new TaskRef('PROJ-Z')
        harness.seedWorkingWithClaim(tracker, z, 'other-instance')

        then: 'the SAME standing reaper — one instance for the whole invocation — still reaps it while the second task is in flight'
        new PollingConditions(timeout: 3, initialDelay: 0, delay: 0.05).eventually {
            tracker.fetchTask(z).state() instanceof TrackerTaskState.Ready
        }

        when: 'the batch completes'
        Throwable thrown = null
        try {
            run.get(15, TimeUnit.SECONDS)
        } catch (ExecutionException e) {
            thrown = e.cause
        }

        then: 'both tasks delivered'
        thrown instanceof TakeExitCodeException
        (thrown as TakeExitCodeException).exitCode() == 0
        tracker.fetchTask(x2).state() instanceof TrackerTaskState.Finished

        cleanup:
        executor.shutdownNow()
    }
}
