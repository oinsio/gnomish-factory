package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.RecordingKiller
import com.github.oinsio.gnomish.app.serve.ServeShutdown
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10, FR11, NFR-O2, M3, D9 of add-factory-serve: {@link ServeShutdownWiring}'s two entry points
 * — the drain path and the forever-loop path — each attach the drain-report/drive-the-automaton
 * side effects and register the same JVM shutdown-hook shape around a {@link ServeShutdown}. Real
 * JVM shutdown-hook registration ({@code Runtime.addShutdownHook}) is seamed behind {@link
 * ServeShutdownWiring.ShutdownHookRegistrar} so these specs can verify a hook was actually
 * registered instead of trusting an untestable direct {@code Runtime} call. {@link TakeSlotRunner}
 * and {@link FeedAutomaton} are both final production classes with no mocking support in this
 * project (no mockito-inline on the classpath) — this spec builds real instances (same pattern as
 * {@code TakeSlotRunnerSpec}) rather than mocking them.
 */
class ServeShutdownWiringSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    Tracker tracker = Mock()

    def setup() {
        def gitRunner = new GitProcessRunner()
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private TakeSlotRunner newSlotRunner() {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeSlotRunner(
                newAssembly(), cloneDir, worktreesRoot, pipeline(), abortHandler, 3, 'taskId',
                [], ClaimBeat.NONE, new ClaimLossFlag(), tracker, INSTANCE)
    }

    /** A real, quick-to-drain automaton: the mocked tracker reports nothing eligible. */
    private FeedAutomaton newAutomaton(TakeSlotRunner slotRunner) {
        new FeedAutomaton(
                tracker, INSTANCE, new SlotLedger(1), slotRunner,
                { Duration d -> } as Sleeper, new SystemClock(), Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofMillis(1), 1, new Random(0))
    }

    // This spec is about ServeShutdownWiring's hook registration/drain/join plumbing, not the
    // standing reaper (fix-reaper-idle-liveness FR4, covered by ServeShutdownSpec) — an inert,
    // never-started StandingReaper is a harmless collaborator here.
    private static ServeShutdown newShutdown(RecordingKiller killer) {
        def inertReaper = new StandingReaper(
                ReaperDuty.NONE, { Duration d -> } as Sleeper, Duration.ofSeconds(30), { [] } as Supplier)
        new ServeShutdown(new SlotLedger(1), new ClaimLossFlag(), Duration.ofMillis(10), killer, inertReaper)
    }

    // FR10, FR11, NFR-O2, D9: proves the three void calls PIT found survived on runDrain's own
    // lines — attachDrainReport, addShutdownHook (seamed as ShutdownHookRegistrar), and
    // automaton.drain() — all actually happen, not just that runDrain returns without error.
    def "runDrain attaches a drain report, drains the automaton, and registers the shutdown hook"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> capturedHook = hook }

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, registrar)

        then: 'a fresh drain report was attached to the slot runner before draining (FR10)'
        slotRunner.@drainReport != null

        and: 'the automaton actually drained — its one empty poll reached the real tracker'
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and: 'the shutdown hook was registered, named as production expects (FR11, D9)'
        capturedHook != null
        capturedHook.name == ServeShutdownWiring.SHUTDOWN_HOOK_THREAD_NAME
    }

    // FR11, D9: drain runs on the calling thread — there is no feed thread to interrupt — so the
    // registered hook's body must call ServeShutdown#shutdown with a null feed thread.
    def "runDrain's registered hook invokes ServeShutdown#shutdown with a null feed thread"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def killer = new RecordingKiller()
        def shutdown = newShutdown(killer)
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> capturedHook = hook }
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, registrar)
        capturedHook.run()

        then: 'the real ServeShutdown sequence ran to completion (proves shutdown(null) was called)'
        killer.calls.get() == 1
    }

    // FR11, D9: the forever-loop path's own survived mutant — addShutdownHook (seamed as
    // ShutdownHookRegistrar) — actually gets registered around the started feed thread.
    def "runForever starts the feed thread and registers the shutdown hook around it"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> capturedHook = hook }
        FeedAutomatonStarter starter = { FeedAutomaton a -> }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, registrar)

        then: 'the shutdown hook was registered, named as production expects (FR11, D9)'
        capturedHook != null
        capturedHook.name == ServeShutdownWiring.SHUTDOWN_HOOK_THREAD_NAME
    }

    // FR11: the running feed thread is the one ServeShutdown#shutdown is asked to interrupt.
    def "runForever's registered hook invokes ServeShutdown#shutdown with the running feed thread"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def killer = new RecordingKiller()
        def shutdown = newShutdown(killer)
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> capturedHook = hook }
        FeedAutomatonStarter starter = { FeedAutomaton a -> Thread.sleep(50) }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, registrar)
        capturedHook.run()

        then: 'the real ServeShutdown sequence ran to completion (proves shutdown(feedThread) was called)'
        killer.calls.get() == 1
    }

    // FR11, D9: runForever must not return until the feed thread has actually finished (kills the
    // removed feedThread.join() mutant) — without the join, runForever would return as soon as
    // feedThread.start() fires, racing ahead of the starter's work below.
    def "runForever does not return until the feed thread has finished its work"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> }
        def starterFinished = new AtomicReference<Boolean>(false)
        FeedAutomatonStarter starter = { FeedAutomaton a ->
            Thread.sleep(200)
            starterFinished.set(true)
        }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, registrar)

        then: 'the starter had already completed by the time runForever returned'
        starterFinished.get()
    }

    // FR11, D9: runFeedLoop's own private catch block (the SIGTERM path on the feed thread itself)
    // — starter.start blocks on an interruptible wait, the shutdown hook's feedThread.interrupt()
    // (fired here via capturedHook.run(), the same call site production's real Runtime hook makes)
    // wakes it with InterruptedException, and runFeedLoop must restore the interrupt status via
    // Thread.currentThread().interrupt() rather than swallowing it (PIT NO_COVERAGE on that call,
    // line ~105). Without that restore, the CountDownLatch#await() throw already clears the flag,
    // so the feed thread would finish with isInterrupted() == false and the mutant would survive.
    def "runFeedLoop restores the interrupt status when the shutdown hook interrupts a blocked feed thread"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook -> capturedHook = hook }
        def starterRunning = new CountDownLatch(1)
        def blockForever = new CountDownLatch(1)
        AtomicReference<Thread> feedThreadRef = new AtomicReference<>()
        FeedAutomatonStarter starter = { FeedAutomaton a ->
            feedThreadRef.set(Thread.currentThread())
            starterRunning.countDown()
            blockForever.await()
        }

        when: 'runForever is driven on its own thread, since it blocks joining the feed thread'
        def runnerThread = new Thread({ ServeShutdownWiring.runForever(automaton, shutdown, starter, registrar) })
        runnerThread.start()

        and: 'wait until the feed thread is actually blocked inside the starter'
        boolean started = starterRunning.await(5, TimeUnit.SECONDS)

        and: 'fire the shutdown hook exactly as the real SIGTERM handler would — interrupts the feed thread'
        capturedHook.run()

        and: 'the feed loop must unblock and runForever must return, not hang'
        runnerThread.join(5000)

        then:
        started
        !runnerThread.isAlive()

        and: 'the interrupt status was restored on the feed thread, not lost (kills the removed-interrupt() mutant)'
        feedThreadRef.get() != null
        feedThreadRef.get().isInterrupted()
    }
}
