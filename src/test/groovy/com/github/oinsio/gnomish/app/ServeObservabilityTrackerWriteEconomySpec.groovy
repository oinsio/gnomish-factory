package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.serve.DirtyNotifier
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.SlotRunner
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * NFR-P1/M4 of add-serve-observability: the task 5.1 observability wiring — {@link
 * TrackerHealthTracker} (D12), the {@link DirtyNotifier} triggers on {@link SlotLedger}/{@link
 * FeedAutomaton}, and the {@code taskOutcome} ledger write point on a finishing slot — writes only
 * to local files and must add zero {@link Tracker}-port calls. Since {@code ServeProperties} has
 * no observability on/off knob (always-on by design; confirmed by inspection — no toggle exists),
 * the comparison this spec draws is over the seam that actually exists: {@link FeedAutomaton}/
 * {@link SlotLedger}/{@link SlotRunner} constructed the way {@code ServeCommand} built them before
 * task 5.1 (bare {@link Tracker}, {@link DirtyNotifier#NOOP}, no ledger write point) versus the
 * way it builds them today (every task 5.1 collaborator attached) — driving the identical scripted
 * drain scenario through both and diffing the resulting {@link Tracker} call logs.
 *
 * <p>{@link TaskOutcomeLedgerWriter}/{@link RunSummaryAccumulator} additionally take no {@link
 * Tracker}-typed constructor argument at all — structurally unable to call the tracker regardless
 * of a slot's body — which this spec's "observed" run exercises for real (a ledger line and an
 * accumulated total actually land) precisely to prove that exercising them costs nothing on the
 * tracker side.
 *
 * <p>Implements NFR-P1, M4 of add-serve-observability.
 */
@Timeout(10)
class ServeObservabilityTrackerWriteEconomySpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final int WIP_LIMIT = 2
    private static final TaskRef REF = new TaskRef('github:o/r#1')
    private static final String INSTANCE_NAME = 'gnomish'
    private static final InstanceInfo INSTANCE_INFO = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')

    @TempDir
    Path homeDir

    /**
     * A hand-rolled recording {@link Tracker}: unlike a Spock interaction {@code Mock}, the SAME
     * instance can be driven through a full scenario twice and its call log compared by value —
     * exactly what proving "identical interactions" needs. Scripts one ready task on the first
     * poll, an empty queue from then on (the drain scenario both runs share).
     */
    static class RecordingTracker implements Tracker {
        final List<String> calls = new CopyOnWriteArrayList<>()
        private final AtomicInteger finishedCount = new AtomicInteger()

        List<ReadyTask> listReady(int limit) {
            calls << "listReady($limit)".toString()
            finishedCount.get() == 0 ? [
                new ReadyTask(REF, AbortFacts.none(), false, false, 'fixture title')
            ] : []
        }

        List<OpenTask> listOpen() {
            calls << 'listOpen()'
            []
        }

        ClaimResult claim(TaskRef ref, String instanceId) {
            calls << "claim(${ref.id()})".toString()
            new ClaimResult.Acquired()
        }

        TrackerTask fetchTask(TaskRef ref) {
            calls << "fetchTask(${ref.id()})".toString()
            null
        }

        void finish(TaskRef ref, String summary) {
            calls << "finish(${ref.id()})".toString()
            finishedCount.incrementAndGet()
        }

        List<HumanReply> collectDecisions(TaskRef ref) {
            []
        }
        void release(TaskRef ref) { }
        void park(TaskRef ref, ParkReason reason, String report) { }
        void recordAbort(TaskRef ref, AbortRecord record) { }
        void recordProgress(TaskRef ref) { }
        void acknowledgeDecision(TaskRef ref, String decisionText) { }
        void postNote(TaskRef ref, String text) { }
        void declineFinished(TaskRef ref, String message) { }

        HeartbeatResult heartbeat(TaskRef ref, String p) {
            null
        }

        RemoveStaleClaimResult removeStaleClaim(
                TaskRef ref, ClaimVersion v) {
            null
        }
    }

    private static TakeResult.Delivered scriptedResult() {
        new TakeResult.Delivered(new TaskState(new Position.AtStage('build'), 1, [], ExecutorUsage.none()), 'shipped it')
    }

    /** The bare {@code SlotRunner} every daemon slot ran before task 5.1: fetch, finish, nothing else. */
    private static SlotRunner bareSlotRunner(Tracker tracker) {
        { TaskRef ref ->
            tracker.fetchTask(ref); tracker.finish(ref, 'shipped it')
        } as SlotRunner
    }

    /** The task 5.1 {@code SlotRunner}: same fetch/finish, plus the ledger line and the run-summary
     * accumulation a real {@code TakeSlotRunner} performs after {@code drainReport.record()}. */
    private static SlotRunner observedSlotRunner(
            Tracker tracker, TaskOutcomeLedgerWriter ledgerWriter, RunSummaryAccumulator accumulator) { {
            TaskRef ref ->
            tracker.fetchTask(ref)
            tracker.finish(ref, 'shipped it')
            def result = scriptedResult()
            accumulator.record(result)
            ledgerWriter.write(ref, result)
        } as SlotRunner
    }

    def "steady-state tracker interactions are identical with the task 5.1 observability collaborators attached or absent (NFR-P1, M4)"() {
        given: 'the bare pre-task-5.1 shape: raw tracker, no-op dirty notifier, no ledger write point'
        def bareTracker = new RecordingTracker()
        def bareClock = new VirtualClock(Instant.parse('2026-01-01T00:00:00Z'))
        def bareLedger = new SlotLedger(1, bareClock, DirtyNotifier.NOOP)
        def bareAutomaton = new FeedAutomaton(bareTracker, INSTANCE, bareLedger, bareSlotRunner(bareTracker),
                new BudgetedVirtualSleeper(bareClock), bareClock, BASE, CAP, IDLE, WIP_LIMIT, new Random(1), DirtyNotifier.NOOP)

        and: 'the observed task 5.1 shape: TrackerHealthTracker (D12), a live dirty notifier, and the taskOutcome ledger write point'
        def observedTracker = new RecordingTracker()
        def observedClock = new VirtualClock(Instant.parse('2026-01-01T00:00:00Z'))
        def healthTracker = new TrackerHealthTracker(observedTracker, observedClock)
        def dirtyCalls = new AtomicInteger()
        DirtyNotifier notifier = {
            dirtyCalls.incrementAndGet()
        } as DirtyNotifier
        def observedLedger = new SlotLedger(1, observedClock, notifier)
        // TaskOutcomeLedgerWriter/RotatingLedgerAppender take a java.time.Clock, distinct from the
        // domain Clock FeedAutomaton/SlotLedger/TrackerHealthTracker use; fixed to the same instant.
        def ledgerClock = Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(homeDir.resolve('placeholder'), new LedgerJsonMapper()), homeDir, INSTANCE_NAME, ledgerClock)
        def ledgerWriter = new TaskOutcomeLedgerWriter(observedLedger, appender, INSTANCE_INFO, ledgerClock)
        def accumulator = new RunSummaryAccumulator()
        def observedAutomaton = new FeedAutomaton(healthTracker, INSTANCE, observedLedger,
                observedSlotRunner(healthTracker, ledgerWriter, accumulator),
                new BudgetedVirtualSleeper(observedClock), observedClock, BASE, CAP, IDLE, WIP_LIMIT, new Random(1), notifier)

        when: 'the identical scripted drain scenario runs through both'
        bareAutomaton.drain()
        observedAutomaton.drain()

        then: 'the exact same sequence of tracker calls happened in both — same methods, same arguments, same order'
        observedTracker.calls == bareTracker.calls
        !bareTracker.calls.isEmpty()

        and: 'the observed run genuinely exercised its collaborators rather than skipping them by construction'
        dirtyCalls.get() > 0
        accumulator.counts().delivered() == 1
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.of(2026, 1, 1))
        Files.readString(ledgerFile).contains('taskOutcome')
    }
}
