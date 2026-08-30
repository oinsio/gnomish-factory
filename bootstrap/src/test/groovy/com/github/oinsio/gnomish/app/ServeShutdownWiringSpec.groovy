package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState
import com.github.oinsio.gnomish.app.serve.DirtyNotifier
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import com.github.oinsio.gnomish.app.serve.RecordingKiller
import com.github.oinsio.gnomish.app.serve.ServeShutdown
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter
import com.github.oinsio.gnomish.serveobservability.writer.SweepLedgerWriter
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

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
// Bound every feature: these start a real SnapshotWriter thread on a 30s interval, so a wake/stop
// mutant that drops the immediate wake would otherwise leave a shutdown test blocked on the worker
// for the full interval — surfacing as a PIT TIMED_OUT (a gate failure) rather than a fast red kill.
@Timeout(10)
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
                newAssembly(), TaskGitFixture.real(), cloneDir, worktreesRoot, pipeline(), abortHandler, 3, 'taskId',
                [], ClaimBeat.NONE, new ClaimLossFlag(), tracker, INSTANCE, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())
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
                ReaperDuty.NONE, { Duration d -> } as Sleeper, Duration.ofSeconds(30), {
                    []
                } as Supplier, new SystemClock())
        new ServeShutdown(new SlotLedger(1), new ClaimLossFlag(), Duration.ofMillis(10), killer, inertReaper)
    }

    // FR1, FR4, FR12 of add-serve-observability (task 5.1): a real ObservabilityWiring, built
    // exactly like ObservabilityWiringSpec's own fixture, so runDrain/runForever's new observability
    // arguments exercise genuine collaborators rather than a mock.
    private ObservabilityWiring newObservability() {
        def clock = Clock.systemUTC()
        def instance = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
        def lifecycleTracker = new LifecycleStateTracker(clock.instant())
        def snapshotWriter = new SnapshotWriter(
                tempDir.resolve('snapshot.json'),
                { -> fixtureSnapshot(lifecycleTracker) },
                new SnapshotJsonMapper(), Duration.ofSeconds(30), clock, 0)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(tempDir.resolve('placeholder'), new LedgerJsonMapper()), tempDir, 'gnomish', clock)
        def lifecycleLedgerWriter = new LifecycleLedgerWriter(appender, instance, clock)
        def taskOutcomeLedgerWriter = new TaskOutcomeLedgerWriter(new SlotLedger(1), appender, instance, clock)
        snapshotWriter.start()
        new ObservabilityWiring(
                lifecycleTracker,
                snapshotWriter,
                lifecycleLedgerWriter,
                taskOutcomeLedgerWriter,
                new SweepLedgerWriter(appender, instance, clock),
                appender,
                instance,
                clock)
    }

    // Task 6.3: same as newObservability(), but the lifecycleTracker's DirtyNotifier records every
    // state it actually transitioned THROUGH, in order, into recordedStates — since
    // LifecycleStateTracker#stop can jump straight from RUNNING to STOPPED with no validation, the
    // final view() alone cannot prove an intermediate beginDraining()/beginStopping() call actually
    // ran; the recorded sequence can.
    private ObservabilityWiring newObservability(List<DaemonLifecycleState> recordedStates) {
        def clock = Clock.systemUTC()
        def instance = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
        def lifecycleTracker
        def notifier = {
            -> recordedStates << lifecycleTracker.view().state()
        } as DirtyNotifier
        lifecycleTracker = new LifecycleStateTracker(clock.instant(), notifier)
        def snapshotWriter = new SnapshotWriter(
                tempDir.resolve('snapshot-recording.json'),
                { -> fixtureSnapshot(lifecycleTracker) },
                new SnapshotJsonMapper(), Duration.ofSeconds(30), clock, 0)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(tempDir.resolve('placeholder-recording'), new LedgerJsonMapper()),
                tempDir, 'gnomish-recording', clock)
        def lifecycleLedgerWriter = new LifecycleLedgerWriter(appender, instance, clock)
        def taskOutcomeLedgerWriter = new TaskOutcomeLedgerWriter(new SlotLedger(1), appender, instance, clock)
        snapshotWriter.start()
        new ObservabilityWiring(
                lifecycleTracker,
                snapshotWriter,
                lifecycleLedgerWriter,
                taskOutcomeLedgerWriter,
                new SweepLedgerWriter(appender, instance, clock),
                appender,
                instance,
                clock)
    }

    // Task 6.3, FR12/FR13: an observability whose lifecycleTracker fires the captured shutdown hook
    // the moment it transitions to STOPPING — i.e. AFTER runDrain's body has run
    // drainCompleted.set(true) (that set sits between automaton.drain() and beginStopping()). This
    // lets a synchronous spec drive the hook inside the one window where the drainCompleted flag it
    // reads is already true, so the flag's value is observable in the finalized reason.
    private ObservabilityWiring newObservabilityFiringHookOnStopping(AtomicReference<Thread> hookRef) {
        def clock = Clock.systemUTC()
        def instance = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
        def lifecycleTracker
        def notifier = {
            ->
            if (lifecycleTracker.view().state() == DaemonLifecycleState.STOPPING) {
                def hook = hookRef.getAndSet(null)
                if (hook != null) {
                    hook.run()
                }
            }
        } as DirtyNotifier
        lifecycleTracker = new LifecycleStateTracker(clock.instant(), notifier)
        def snapshotWriter = new SnapshotWriter(
                tempDir.resolve('snapshot-hookfire.json'),
                { -> fixtureSnapshot(lifecycleTracker) },
                new SnapshotJsonMapper(), Duration.ofSeconds(30), clock, 0)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(tempDir.resolve('placeholder-hookfire'), new LedgerJsonMapper()),
                tempDir, 'gnomish-hookfire', clock)
        def lifecycleLedgerWriter = new LifecycleLedgerWriter(appender, instance, clock)
        def taskOutcomeLedgerWriter = new TaskOutcomeLedgerWriter(new SlotLedger(1), appender, instance, clock)
        snapshotWriter.start()
        new ObservabilityWiring(
                lifecycleTracker,
                snapshotWriter,
                lifecycleLedgerWriter,
                taskOutcomeLedgerWriter,
                new SweepLedgerWriter(appender, instance, clock),
                appender,
                instance,
                clock)
    }

    private static Snapshot fixtureSnapshot(LifecycleStateTracker tracker) {
        def instance = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
        def feed = new FeedSnapshot(FeedPhase.IDLE_EMPTY, Instant.EPOCH, Instant.EPOCH, 0, 1)
        def slots = new SlotsSnapshot(1, [])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.RUNNING, Instant.EPOCH, 0),
                new ReaperVital(Instant.EPOCH, 0, 300L),
                new JanitorVital(Instant.EPOCH))
        new Snapshot(1, Instant.EPOCH, 0L, instance, LifecycleSnapshotAssembler.assemble(tracker), feed, slots, vitals, new TrackerHealth(null, 0))
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
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, newObservability(), registrar)

        then: 'a fresh drain report was attached to the slot runner before draining (FR10)'
        slotRunner.@drainReport != null

        and: 'the automaton actually drained — its one empty poll reached the real tracker'
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and: 'the shutdown hook was registered, named as production expects (FR11, D9)'
        capturedHook != null
        capturedHook.name == ServeShutdownWiring.SHUTDOWN_HOOK_THREAD_NAME
    }

    // FR4, FR12, FR13 of add-serve-observability (task 5.1): runDrain drives the SAME observability
    // wiring through draining -> stopping -> stopped(drainComplete), and writes the drain run's
    // runSummary line — proving the ledger writer is genuinely reached, not merely constructed.
    def "runDrain writes a runSummary line and a stopped(drainComplete) lifecycle line"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        def observability = newObservability()
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, observability, { Thread hook -> })

        then: 'the lifecycle tracker landed on stopped(drainComplete) — not left mid-sequence'
        observability.@lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        observability.@lifecycleTracker.view().reason() == 'drainComplete'

        and: 'a runSummary line and a stopped lifecycle line both landed in the ledger'
        def ledgerFile = ObservabilityPaths.ledgerFile(
                tempDir, 'gnomish', LocalDate.now(ZoneOffset.UTC))
        def lines = Files.readString(ledgerFile)
        lines.contains('"type":"runSummary"')
        lines.contains('"event":"stopped"')
        lines.contains('"reason":"drainComplete"')
    }

    // Task 6.3, FR4: runDrain's own body must actually MOVE the daemon through DRAINING then
    // STOPPING before finalizing — not just land on STOPPED (which LifecycleStateTracker#stop can
    // reach directly from RUNNING with no validation, so the terminal state alone cannot prove the
    // intermediate calls ran). The recording observability captures every state the tracker
    // actually passed through, in order.
    def "runDrain moves the daemon through DRAINING then STOPPING before finalizing to STOPPED"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        def recordedStates = []
        def observability = newObservability(recordedStates)
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when: 'the hook is registered but never fired — only runDrain own direct calls can act'
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, observability, { Thread hook -> })

        then:
        recordedStates == [
            DaemonLifecycleState.DRAINING,
            DaemonLifecycleState.STOPPING,
            DaemonLifecycleState.STOPPED,
        ]
    }

    // Task 6.3, FR1, FR13: proves attachRunSummaryAccumulator really attaches a non-null
    // accumulator to the slot runner — the class Javadoc's own PIT survivor.
    def "runDrain attaches a RunSummaryAccumulator to the slot runner"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, newObservability(), { Thread hook -> })

        then:
        slotRunner.@runSummaryAccumulator != null
    }

    // Task 6.3, FR4, D9, FR12/FR13/UX4: proves the hook's OWN observability.finalizeStopped call
    // (not just runDrain's main-body call after it) genuinely finalizes — by making the hook fire
    // BEFORE the main body reaches its own calls (a SIGTERM landing before drain completes). The
    // drainCompleted flag is still clear, so the hook finalizes with reason "sigterm", not
    // "drainComplete" — an interrupted drain is not a completed one (П1). STOPPED is terminal, so
    // the main body's later beginDraining()/beginStopping() are no-ops and cannot drag the final
    // snapshot back to a non-terminal state (П2, FR4). Both assertions are possible ONLY if the
    // hook's finalizeStopped call actually ran first: were it removed (the mutant), the main body
    // would finalize normally to stopped("drainComplete").
    def "runDrain's shutdown hook finalizes first as stopped(sigterm) when SIGTERM lands before drain completes"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        def observability = newObservability()
        ServeShutdownWiring.ShutdownHookRegistrar immediateRegistrar = { Thread hook ->
            hook.run()
        }
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, observability, immediateRegistrar)

        then: 'the hook won the finalize with reason sigterm, and STOPPED is terminal — not dragged back'
        observability.@lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        observability.@lifecycleTracker.view().reason() == 'sigterm'
    }

    // Task 6.3, FR12/FR13/UX4: runDrain sets drainCompleted BETWEEN the drain finishing and the
    // beginStopping() transition, so a hook that fires after drain completes truthfully reports
    // "drainComplete". Firing the captured hook exactly at the STOPPING transition (via the tracker's
    // notifier) lands it in that window with drainCompleted already true, so it finalizes
    // "drainComplete". Remove the drainCompleted.set(true) call (the surviving VoidMethodCall mutant)
    // and the same hook would read false and finalize "sigterm" — flipping this assertion.
    def "runDrain sets drainCompleted before stopping, so a hook firing after drain finalizes drainComplete not sigterm"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        def hookRef = new AtomicReference<Thread>()
        def observability = newObservabilityFiringHookOnStopping(hookRef)
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            hookRef.set(hook)
        }
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, observability, registrar)

        then: 'the hook won the finalize while drainCompleted was already true — reason drainComplete'
        observability.@lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        observability.@lifecycleTracker.view().reason() == 'drainComplete'
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
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown, newObservability(), registrar)
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
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }
        FeedAutomatonStarter starter = { FeedAutomaton a -> }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, newObservability(), registrar)

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
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }
        FeedAutomatonStarter starter = { FeedAutomaton a -> Thread.sleep(50) }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, newObservability(), registrar)
        capturedHook.run()

        then: 'the real ServeShutdown sequence ran to completion (proves shutdown(feedThread) was called)'
        killer.calls.get() == 1
    }

    // FR4, FR12 of add-serve-observability (task 5.1): the SIGTERM hook drives observability
    // through draining -> stopping -> stopped(sigterm) and appends the stopped ledger line —
    // exactly the sequence the class Javadoc describes for the forever-loop path.
    def "runForever's shutdown hook moves observability through draining, stopping, and stopped(sigterm)"() {
        given:
        def slotRunner = newSlotRunner()
        def automaton = newAutomaton(slotRunner)
        def shutdown = newShutdown(new RecordingKiller())
        def recordedStates = []
        def observability = newObservability(recordedStates)
        Thread capturedHook = null
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }
        FeedAutomatonStarter starter = { FeedAutomaton a -> Thread.sleep(50) }

        when:
        ServeShutdownWiring.runForever(automaton, shutdown, starter, observability, registrar)
        capturedHook.run()

        then:
        observability.@lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        observability.@lifecycleTracker.view().reason() == 'sigterm'

        and: 'the hook actually MOVED the tracker through draining then stopping (task 6.3) — not ' +
        'just landed on stopped, which #stop() can reach directly from any prior state'
        recordedStates == [
            DaemonLifecycleState.DRAINING,
            DaemonLifecycleState.STOPPING,
            DaemonLifecycleState.STOPPED,
        ]

        and:
        def ledgerFile = ObservabilityPaths.ledgerFile(
                tempDir, 'gnomish-recording', LocalDate.now(ZoneOffset.UTC))
        Files.readString(ledgerFile).contains('"reason":"sigterm"')
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
        ServeShutdownWiring.runForever(automaton, shutdown, starter, newObservability(), registrar)

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
        ServeShutdownWiring.ShutdownHookRegistrar registrar = { Thread hook ->
            capturedHook = hook
        }
        def starterRunning = new CountDownLatch(1)
        def blockForever = new CountDownLatch(1)
        AtomicReference<Thread> feedThreadRef = new AtomicReference<>()
        FeedAutomatonStarter starter = { FeedAutomaton a ->
            feedThreadRef.set(Thread.currentThread())
            starterRunning.countDown()
            blockForever.await()
        }

        when: 'runForever is driven on its own thread, since it blocks joining the feed thread'
        def runnerThread = new Thread({
            ServeShutdownWiring.runForever(automaton, shutdown, starter, newObservability(), registrar)
        })
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
