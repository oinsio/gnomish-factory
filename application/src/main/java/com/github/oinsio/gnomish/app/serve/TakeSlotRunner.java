package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.RunOrder;
import com.github.oinsio.gnomish.app.SlotWiring;
import com.github.oinsio.gnomish.app.TakeClaimAndWork;
import com.github.oinsio.gnomish.app.TakeClaimAndWorkFactory;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.WallTime;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The real {@link SlotRunner}: given only an already-claimed {@link TaskRef} (task 4.2's seam),
 * fetches the {@link TrackerTask} and runs it through the exact same take cycle a single explicit
 * {@code take <ref>} would — {@link TakeClaimAndWork#workClaimed} — so escalation,
 * abort, and revocation behave identically to a single {@code take} of that task (FR1, M2: "slot
 * body unchanged").
 *
 * <p>Built once and reused across every slot invocation over the daemon's lifetime (unlike {@code
 * TakeBareAuto}, which {@code TakeDispatcher} builds fresh per bare-take run): the constructor
 * wires a single {@link TakeClaimAndWork} via {@link TakeClaimAndWorkFactory#forSlot} up front.
 *
 * <p>MDC: since {@link FeedAutomaton} starts one fresh virtual thread per slot and MDC is
 * thread-local, setting the {@code taskId} key inside {@link #run(TaskRef)} tags only that slot's
 * own logs, cleared in a {@code finally} even though these threads are never reused.
 *
 * <p><b>Exception boundary (deliberate).</b> {@link TakeClaimAndWork#workClaimed} already
 * funnels ordinary {@code RuntimeException}s through its own crash-abort protocol, rethrowing only
 * {@code UsageException} unchanged — but a slot must never let anything escape {@link
 * #run(TaskRef)}: {@link FeedAutomaton} installs no uncaught-exception handler on its virtual
 * thread. This class catches every {@link Throwable} here, hands it to {@link SlotOutcomeLog} and
 * swallows it — a failed slot must not take down the daemon. Implements FR1, M2 of add-factory-serve.
 *
 * <p><b>Remote outage gate (task 7.3 of add-base-ref-resolution, FR14).</b> The slot signals the
 * shared {@link RemoteOutageGate} from its base reads themselves, not from its terminal {@link
 * TakeResult}: the {@link com.github.oinsio.gnomish.app.port.git.BaseRefGit} inside the wiring's
 * {@link com.github.oinsio.gnomish.app.port.git.TaskGit} is already wrapped in {@link
 * RemoteOutageSignalingBaseRefGit} by the serve assembly point, through {@link
 * RemoteOutageGates#signaling} (D6 of introduce-slot-wiring) — the slot reads through it without
 * knowing it and never decorates its own equipment. An outage opens the gate and a refresh
 * confirms recovery at the instant either happens — a terminal result arrives hours after the
 * refresh it implies (see that class for the stale-signal defect this closes). Opening the gate
 * never touches an in-flight slot: it only changes what the NEXT feed cycle's {@link
 * FeedCycle#claimOrAbandon} does.
 *
 * <p><b>Late sinks (D7 of fix-operator-blockers).</b> Three sinks arrive after construction, as
 * {@code process-invariants.md} ("Immutable after construction") permits only for a named cycle:
 * the ledger writer, because the slot runner is built before the feed automaton and the
 * observability wiring that owns the ledger appender needs that automaton ({@code
 * ServeRuntimeAssembly}); the drain report and the run-summary accumulator, because the drain
 * path creates them itself after assembly and only in drain mode ({@code ServeShutdownWiring}).
 * Each field is {@code volatile}, so a slot thread sees a sink set before it runs whatever order
 * the threads were started in, and {@link #run(TaskRef)} reads each once and treats {@code null}
 * as not attached. Implements FR10, NFR-R2 of fix-operator-blockers.
 */
public final class TakeSlotRunner implements SlotRunner {

    private static final Logger log = LoggerFactory.getLogger(TakeSlotRunner.class);

    private final TakeClaimAndWork claimAndWork;
    private final RunOrder run;
    private final Tracker tracker;
    private final InstanceId instanceId;
    private final String taskIdMdcKey;
    private final SlotOutcomeLog outcomeLog = new SlotOutcomeLog(log);
    private volatile @Nullable DrainReport drainReport;
    private volatile @Nullable TaskOutcomeLedgerWriter ledgerWriter;
    private volatile @Nullable RunSummaryAccumulator runSummaryAccumulator;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring), built once per daemon and
     *     shared by every slot; its task git already reports every base read to the daemon's
     *     remote outage gate (D6), and its MDC key is set to the claimed ref's id for the slot's
     *     duration; never null
     * @param run the run order every slot dispatches under: the project clone, the loaded pipeline
     *     every slot advances through, and serve's fixed no-base, salvaging
     *     settings; never null
     * @param tracker the tracker port every slot fetches and dispatches through; never null
     * @param instanceId this factory instance's identity; never null
     */
    public TakeSlotRunner(SlotWiring wiring, RunOrder run, Tracker tracker, InstanceId instanceId) {
        this.claimAndWork = new TakeClaimAndWorkFactory(wiring).forSlot();
        this.run = run;
        this.tracker = tracker;
        this.instanceId = instanceId;
        this.taskIdMdcKey = wiring.taskIdMdcKey();
    }

    /**
     * Attaches {@code report} so every future {@link #run(TaskRef)} call also records its terminal
     * outcome into it, alongside the existing log line. Drain-only: {@code ServeShutdownWiring}
     * attaches one only when {@code --drain} is set, so this is a no-op for the normal path.
     *
     * <p>Implements FR10, NFR-O2 of add-factory-serve.
     *
     * @param report the drain run's closing-report sink; never null
     */
    public void attachDrainReport(DrainReport report) {
        this.drainReport = report;
    }

    /**
     * Attaches {@code writer} so a future {@link #run(TaskRef)} also appends a {@code
     * taskOutcome} ledger line beside the existing log line; mirrors {@link #attachDrainReport}'s
     * optional style. Implements FR11.
     * @param writer the ledger write point; never null
     */
    public void attachLedgerWriter(TaskOutcomeLedgerWriter writer) {
        this.ledgerWriter = writer;
    }

    /**
     * Attaches {@code accumulator} so every future {@link #run(TaskRef)} call also records its
     * terminal result into it, beside {@link #attachDrainReport}'s own call — the totals a {@code
     * runSummary} line is built from once the drain run completes. Drain-only, mirroring {@link
     * #attachDrainReport}. Implements FR13, D6 of add-serve-observability.
     *
     * @param accumulator the drain run's in-memory {@code runSummary} accumulator; never null
     */
    public void attachRunSummaryAccumulator(RunSummaryAccumulator accumulator) {
        this.runSummaryAccumulator = accumulator;
    }

    /**
     * Runs {@code claimed} to a terminal {@link TakeResult}, logs the outcome, and never
     * propagates a throwable (see class javadoc's exception-boundary note).
     *
     * <p>Implements FR1, M2, FR4 of add-factory-serve.
     *
     * @param claimed the just-claimed task's identity; never null
     */
    @Override
    public void run(TaskRef claimed) {
        MDC.put(taskIdMdcKey, claimed.id());
        long startedNanos = System.nanoTime();
        try {
            // The order for an already-claimed task is assembled by its one owner (FR6 of
            // collapse-composition-roots), shared with bare take's claim walk.
            TakeResult result = claimAndWork.workClaimed(run, claimed, tracker, instanceId);
            outcomeLog.detail(claimed, result);
            DrainReport report = drainReport;
            if (report != null) {
                report.record(claimed, result);
            }
            RunSummaryAccumulator accumulator = runSummaryAccumulator;
            if (accumulator != null) {
                accumulator.record(result);
            }
            TaskOutcomeLedgerWriter writer = ledgerWriter;
            if (writer != null) {
                writer.write(claimed, result);
            }
            outcomeLog.summarize(result, WallTime.since(startedNanos));
        } catch (Throwable crash) {
            // Deliberate boundary: see class javadoc. A slot never crashes the daemon.
            outcomeLog.crashed(claimed, crash, WallTime.since(startedNanos));
        } finally {
            MDC.remove(taskIdMdcKey);
            // FR8: backstop for a slot that ended without TaskFinished — a crash caught at the
            // boundary above leaves the engine's stage/attempt keys on this carrier thread.
            MdcEventListener.clearAttemptScope();
        }
    }
}
