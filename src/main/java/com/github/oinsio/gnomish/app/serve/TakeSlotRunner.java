package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.ManualRunAssembly;
import com.github.oinsio.gnomish.app.RunArguments;
import com.github.oinsio.gnomish.app.TakeClaimAndWork;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
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
 * takes the same collaborators {@code TakeBareAuto} does — a shared {@link ManualRunAssembly}, the
 * worktrees root, an {@link AbortHandler}, the abort-fuse threshold, the MDC key, the credential
 * env vars to scrub, a {@link ClaimBeat} heartbeat, and a {@link ClaimLossFlag} — and wires a
 * single {@link TakeClaimAndWork} via {@link TakeClaimAndWork#forSlot} up front. {@code serve} is
 * unconditionally non-interactive (FR4): {@link RunArguments.InteractiveMode#NONE} is hardcoded,
 * never wired to a TTY dialog.
 *
 * <p>MDC: since {@link FeedAutomaton} starts one fresh virtual thread per slot and calls {@link
 * #run(TaskRef)} directly on it, and MDC is thread-local, setting the {@code taskId} key inside
 * {@link #run(TaskRef)} is correct by construction — each slot thread tags only its own logs. The
 * key is cleared in a {@code finally}, matching {@code TakeCommand#run}'s existing clear pattern,
 * even though these threads are never reused.
 *
 * <p><b>Exception boundary (deliberate).</b> {@link TakeClaimAndWork#dispatchAfterClaim} already
 * funnels ordinary {@code RuntimeException}s through its own crash-abort protocol, rethrowing only
 * {@code UsageException}/{@code DivergedBranchException} unchanged — but a slot must never let
 * anything, including those two, escape {@link #run(TaskRef)}: {@link FeedAutomaton} installs no
 * uncaught-exception handler on the virtual thread it starts, so an escaping throwable would
 * surface only as a JVM-logged uncaught exception, silently dropping the slot without ever
 * releasing visible state beyond the ledger permit ({@link FeedAutomaton} always releases that in
 * its own {@code finally}, regardless). This class therefore catches every {@link Throwable} at
 * this boundary, logs it at ERROR, and swallows it — a failed slot must not take down the daemon
 * or any other slot. This boundary decision may be worth revisiting once the SIGTERM/lifecycle
 * task (section 5) exists: a daemon-level policy might want to count or react to repeated slot
 * crashes rather than silently absorbing every one of them.
 *
 * <p>Implements FR1, M2 of add-factory-serve.
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

    /**
     * @param assembly                 the shared engine/ports assembly, reused across every slot; never null
     * @param cloneDir                 the project clone every slot dispatches against; never null
     * @param worktreesRoot            the root directory under which {@code <project-name>/<taskId>/}
     *                                 worktrees are created; never null
     * @param definition               the loaded pipeline every slot advances through; never null
     * @param abortHandler             the infrastructure-abort protocol; never null
     * @param abortThreshold           the configured abort-fuse threshold (K); positive
     * @param taskIdMdcKey             the MDC key this class sets to the claimed ref's id for the duration of
     *                                 the slot, and clears once it terminates
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
     *                                 environment variable names, threaded down to every engine execution; never null
     * @param heartbeat                the instance heartbeat lifecycle registered/unregistered around the run;
     *                                 {@link ClaimBeat#NONE} when no beat runs
     * @param claimLossFlag            the per-run heartbeat claim-loss flag; never null
     * @param tracker                  the tracker port every slot fetches and dispatches through; never null
     * @param instanceId               this factory instance's identity; never null
     */
    public TakeSlotRunner(
            ManualRunAssembly assembly,
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
            InstanceId instanceId) {
        this.claimAndWork = TakeClaimAndWork.forSlot(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                heartbeat,
                claimLossFlag);
        this.cloneDir = cloneDir;
        this.definition = definition;
        this.tracker = tracker;
        this.instanceId = instanceId;
        this.taskIdMdcKey = taskIdMdcKey;
    }

    /**
     * Attaches {@code report} so every future {@link #run(TaskRef)} call also records its
     * terminal outcome into it, alongside the existing log line. Drain-only (task 5.4 of
     * add-factory-serve): {@code ServeCommand} calls this before {@link FeedAutomaton#drain()}
     * only when {@code --drain} is set; an ordinary run never attaches one, so this stays a
     * no-op for the normal {@code serve} path.
     *
     * <p>Implements FR10, NFR-O2 of add-factory-serve.
     *
     * @param report the drain run's closing-report sink; never null
     */
    public void attachDrainReport(DrainReport report) {
        this.drainReport = report;
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
        } catch (Throwable crash) {
            // Deliberate boundary: see class javadoc. A slot never crashes the daemon.
            log.error("slot for task {} crashed uncaught", claimed.id(), crash);
        } finally {
            MDC.remove(taskIdMdcKey);
        }
    }

    private void logOutcome(TaskRef claimed, TakeResult result) {
        switch (result) {
            case TakeResult.Delivered delivered ->
                log.info("slot for task {} delivered: {}", claimed.id(), delivered.summary());
            case TakeResult.AwaitingHuman awaitingHuman ->
                log.info(
                        "slot for task {} parked ({}): {}",
                        claimed.id(),
                        awaitingHuman.reason(),
                        awaitingHuman.report());
            case TakeResult.Aborted aborted -> log.warn("slot for task {} aborted: {}", claimed.id(), aborted.cause());
            case TakeResult.Revoked revoked -> log.warn("slot for task {} revoked: {}", claimed.id(), revoked.note());
            case TakeResult.Skipped skipped -> log.warn("slot for task {} skipped: {}", claimed.id(), skipped.reason());
            case TakeResult.EmptyQueue _ ->
                log.debug("slot for task {} reported an unexpected empty-queue result", claimed.id());
        }
    }
}
