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
import com.github.oinsio.gnomish.app.take.TaskSummaryAssembler;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import com.github.oinsio.gnomish.status.AnchorLog;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.TaskSummary;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * {@code UsageException} unchanged — but a slot must never let
 * anything escape {@link #run(TaskRef)}: {@link FeedAutomaton} installs no uncaught-exception
 * handler on its virtual thread. This class catches every {@link Throwable} here, logs it at
 * ERROR, and swallows it — a failed slot must not take down the daemon. Implements FR1, M2 of
 * add-factory-serve.
 */
public final class TakeSlotRunner implements SlotRunner {

    private static final Logger log = LoggerFactory.getLogger(TakeSlotRunner.class);

    private final TakeClaimAndWork claimAndWork;
    private final Path cloneDir;
    private final PipelineDefinition definition;
    private final Tracker tracker;
    private final InstanceId instanceId;
    private final String taskIdMdcKey;
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
     * @param taskIdMdcKey the MDC key set to the claimed ref's id for the slot's duration, cleared once it terminates
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
     * Attaches {@code report} so every future {@link #run(TaskRef)} call also records its
     * terminal outcome into it, alongside the existing log line. Drain-only: {@code
     * ServeShutdownWiring} attaches one only when {@code --drain} is set; an ordinary run never
     * attaches one, so this stays a no-op for the normal {@code serve} path.
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
     * runSummary} line is built from once the drain run completes (design D6, FR13). Drain-only,
     * mirroring {@link #attachDrainReport}: unattached on an ordinary run, so standing-mode stop
     * can never produce a {@code runSummary} line. Implements FR13, D6 of add-serve-observability.
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
            logOutcome(claimed, result);
            if (drainReport != null) {
                drainReport.record(claimed, result);
            }
            if (runSummaryAccumulator != null) {
                runSummaryAccumulator.record(result);
            }
            if (ledgerWriter != null) {
                ledgerWriter.write(claimed, result);
            }
            summarize(result, elapsedSince(startedNanos));
        } catch (Throwable crash) {
            // Deliberate boundary: see class javadoc. A slot never crashes the daemon.
            logCrash(claimed, crash);
            summarizeCrash(elapsedSince(startedNanos));
        } finally {
            MDC.remove(taskIdMdcKey);
            // FR8: backstop for a slot that ended without TaskFinished — a crash caught at the
            // boundary above leaves the engine's stage/attempt keys on this carrier thread.
            MdcEventListener.clearAttemptScope();
        }
    }

    /**
     * FR3 of harden-logging-observability: the canonical task summary, emitted last so a {@code
     * grep taskId=<id>} ends on it. {@code EmptyQueue}/{@code Skipped} assemble to no summary —
     * no run happened, so there is nothing to summarize (the same boundary the ledger draws).
     */
    private void summarize(TakeResult result, Duration wall) {
        TaskSummary summary = TaskSummaryAssembler.assemble(result, wall);
        if (summary != null) {
            AnchorLog.taskSummary(summary);
        }
    }

    /**
     * FR3: a task leaving the factory through the crash boundary still gets its one summary — the
     * grep story must not simply stop. The facts a terminal result would have carried are exactly
     * what the crash destroyed, so the line states the outcome and the wall time and claims
     * nothing else: no stage, no attempts, no token totals it cannot know.
     */
    private void summarizeCrash(Duration wall) {
        AnchorLog.taskSummary(new TaskSummary(TaskSummary.Outcome.ABORTED, null, null, 0, wall, Map.of()));
    }

    /** Wall time from a monotonic source: a clock stepped by NTP mid-run must not report a negative run. */
    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    /**
     * FR9 of harden-logging-observability: a slot dying because the daemon is stopping is not a
     * fault of the slot's. During the shutdown phase the round it was in was interrupted on
     * purpose, so the death is recorded once at WARN, naming the exception type but carrying no
     * stack — the stack would describe the stop, not a defect. Outside the phase nothing changed:
     * an uncaught crash is still an ERROR with its full stack.
     */
    private void logCrash(TaskRef claimed, Throwable crash) {
        if (ShutdownPhase.inProgress()) {
            // throwable-not-subject: the stop is the cause, and the classification is the whole
            //     content of the line — a stack here would be noise on every clean shutdown.
            log.warn(
                    "slot for task {} stopped by the daemon shutdown ({})",
                    claimed.id(),
                    crash.getClass().getSimpleName());
            return;
        }
        log.error("slot for task {} crashed uncaught", claimed.id(), crash);
    }

    /**
     * The per-outcome <em>detail</em> line: the free text each terminal variant carries (a delivery
     * summary, a park report, an abort cause) which the canonical summary's fixed vocabulary has no
     * room for.
     *
     * <p>The four variants that produce a summary log their detail at DEBUG (task 4.3 of
     * harden-logging-observability). Each used to state the outcome here at its own level, which
     * with the summary now stating it at the level the outcome warrants would be two lines saying
     * the same thing about one task — and for an infrastructure abort, a third one under
     * {@code AbortHandler}'s own WARN/ERROR naming the cause. One outcome, one level-bearing line:
     * the summary. {@code Skipped} keeps its WARN because no summary is written for it — nothing
     * ran, yet an operator still wants to know the slot declined the task.
     */
    private void logOutcome(TaskRef claimed, TakeResult result) {
        switch (result) {
            case TakeResult.Delivered delivered ->
                log.debug("slot for task {} delivered: {}", claimed.id(), delivered.summary());
            case TakeResult.AwaitingHuman awaitingHuman ->
                log.debug(
                        "slot for task {} parked ({}): {}",
                        claimed.id(),
                        awaitingHuman.reason(),
                        awaitingHuman.report());
            case TakeResult.Aborted aborted -> log.debug("slot for task {} aborted: {}", claimed.id(), aborted.cause());
            case TakeResult.Revoked revoked -> log.debug("slot for task {} revoked: {}", claimed.id(), revoked.note());
            case TakeResult.Skipped skipped -> log.warn("slot for task {} skipped: {}", claimed.id(), skipped.reason());
            case TakeResult.EmptyQueue _ ->
                log.debug("slot for task {} reported an unexpected empty-queue result", claimed.id());
        }
    }
}
