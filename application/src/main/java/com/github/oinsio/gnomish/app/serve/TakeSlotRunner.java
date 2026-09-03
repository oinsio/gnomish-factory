package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.ContainerTakeSupport;
import com.github.oinsio.gnomish.app.RunArguments;
import com.github.oinsio.gnomish.app.RunAssembly;
import com.github.oinsio.gnomish.app.TakeClaimAndWork;
import com.github.oinsio.gnomish.app.TakeClaimAndWorkFactory;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.WallTime;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The real {@link SlotRunner}: given only an already-claimed {@link TaskRef} (task 4.2's seam),
 * fetches the {@link TrackerTask} and runs it through the exact same take cycle a single explicit
 * {@code take <ref>} would — {@link TakeClaimAndWork#dispatchAfterClaim} — so escalation,
 * abort, and revocation behave identically to a single {@code take} of that task (FR1, M2: "slot
 * body unchanged").
 *
 * <p>Built once and reused across every slot invocation over the daemon's lifetime (unlike {@code
 * TakeBareAuto}, which {@code TakeDispatcher} builds fresh per bare-take run): the constructor
 * wires a single {@link TakeClaimAndWork} via {@link TakeClaimAndWorkFactory#forSlot} up front.
 * {@code serve} is unconditionally non-interactive (FR4): {@link RunArguments.InteractiveMode#NONE}.
 *
 * <p>MDC: since {@link FeedAutomaton} starts one fresh virtual thread per slot and MDC is
 * thread-local, setting the {@code taskId} key inside {@link #run(TaskRef)} tags only that slot's
 * own logs, cleared in a {@code finally} even though these threads are never reused.
 *
 * <p><b>Exception boundary (deliberate).</b> {@link TakeClaimAndWork#dispatchAfterClaim} already
 * funnels ordinary {@code RuntimeException}s through its own crash-abort protocol, rethrowing only
 * {@code UsageException} unchanged — but a slot must never let anything escape {@link
 * #run(TaskRef)}: {@link FeedAutomaton} installs no uncaught-exception handler on its virtual
 * thread. This class catches every {@link Throwable} here, hands it to {@link SlotOutcomeLog} and
 * swallows it — a failed slot must not take down the daemon. Implements FR1, M2 of add-factory-serve.
 */
public final class TakeSlotRunner implements SlotRunner {

    private static final Logger log = LoggerFactory.getLogger(TakeSlotRunner.class);

    private final TakeClaimAndWork claimAndWork;
    private final Path cloneDir;
    private final PipelineDefinition definition;
    private final Tracker tracker;
    private final InstanceId instanceId;
    private final String taskIdMdcKey;
    private final SlotOutcomeLog outcomeLog = new SlotOutcomeLog(log);
    private @Nullable DrainReport drainReport;
    private @Nullable TaskOutcomeLedgerWriter ledgerWriter;
    private @Nullable RunSummaryAccumulator runSummaryAccumulator;

    /**
     * @param assembly the shared engine/ports assembly, reused across every slot; never null
     * @param git the task-git capability set every slot's store, branch and worktree operations
     *     come from; never null
     * @param cloneDir the project clone every slot dispatches against; never null
     * @param worktreesRoot the root under which {@code <project-name>/<taskId>/} worktrees are created; never null
     * @param definition the loaded pipeline every slot advances through; never null
     * @param abortHandler the infrastructure-abort protocol; never null
     * @param abortThreshold the configured abort-fuse threshold (K); positive
     * @param taskIdMdcKey the MDC key set to the claimed ref's id for the slot's duration
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential env var names; never null
     * @param heartbeat the heartbeat lifecycle registered/unregistered around the run; {@link ClaimBeat#NONE} when none
     * @param claimLossFlag the per-run heartbeat claim-loss flag; never null
     * @param tracker the tracker port every slot fetches and dispatches through; never null
     * @param instanceId this factory instance's identity; never null
     * @param epochs this instance's tenure record, read by the routing point for the repair line it
     *     leaves on a non-clean pickup (NFR-O1 of harden-task-branch-contract); never null
     */
    public TakeSlotRunner(
            RunAssembly assembly,
            TaskGit git,
            Path cloneDir,
            Path worktreesRoot,
            PipelineDefinition definition,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag,
            Tracker tracker,
            InstanceId instanceId,
            ContainerTakeSupport containerTakeSupport,
            ClaimEpochBook epochs) {
        this.claimAndWork = TakeClaimAndWorkFactory.forSlot(
                assembly,
                git,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                heartbeat,
                claimLossFlag,
                containerTakeSupport,
                epochs);
        this.cloneDir = cloneDir;
        this.definition = definition;
        this.tracker = tracker;
        this.instanceId = instanceId;
        this.taskIdMdcKey = taskIdMdcKey;
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
            TrackerTask trackerTask = tracker.fetchTask(claimed);
            TakeResult result = claimAndWork.dispatchAfterClaim(
                    cloneDir,
                    null,
                    definition,
                    RunArguments.InteractiveMode.NONE,
                    false,
                    trackerTask,
                    tracker,
                    instanceId);
            outcomeLog.detail(claimed, result);
            if (drainReport != null) {
                drainReport.record(claimed, result);
            }
            if (runSummaryAccumulator != null) {
                runSummaryAccumulator.record(result);
            }
            if (ledgerWriter != null) {
                ledgerWriter.write(claimed, result);
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
