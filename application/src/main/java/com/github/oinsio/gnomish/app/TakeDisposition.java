package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.DeclineFinishedMessage;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The explicit-mode ({@code take <ref>}) disposition matrix (proposal FR9, UX2; design D2, D3):
 * given an already-fetched {@link TrackerTask}, dispatches per its {@link TrackerTaskState} —
 * {@code Ready} claims and works it (fresh or resumed) unless also {@code finished} (reopened by a
 * human), which refuses via the decline protocol instead (FR5); {@code AwaitingHuman} refuses
 * without mutating the tracker, {@code Working} (held by another instance) enters the {@link
 * TakeTakeover} confirmation path (task 6.2 of add-claim-heartbeat, FR6), {@code Finished}/{@code
 * Gone} skip. The operator mandate overrides the readiness criterion and abort backoff (FR9)
 * simply by never consulting either: this class only reads the state {@code fetchTask} already
 * reported. The same omission pierces the WIP limit for {@code Ready} tasks (FR8 of
 * add-factory-serve): neither {@link com.github.oinsio.gnomish.app.take.OpenFrontGate} nor any
 * open-front count is consulted here, so the mandate is unconditional for a {@code Ready} target;
 * {@code AwaitingHuman} keeps the existing refusal, {@code Working} keeps the takeover protocol.
 *
 * <p>Short-ref expansion (`42`, `#42`) and CLI argument parsing/Spring wiring are later concerns
 * (tasks 5.13/5.14, not built here) — {@link #dispose} takes an already-resolved {@link TaskRef};
 * this class is the plain, constructor-injectable entry point command wiring calls into.
 *
 * <p>Implements FR9, UX2, D2, D3 of add-tracker-port; FR6 of add-claim-heartbeat; FR8 of
 * add-factory-serve; FR5 of enforce-finish-terminality.
 */
final class TakeDisposition {

    private static final Logger log = LoggerFactory.getLogger(TakeDisposition.class);

    private final TakeClaimAndWork claimAndWork;
    private final TakeTakeover takeover;

    /**
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
     * @param git the task-git capability set every claim/resume path's store, branch and worktree
     *     operations come from; never null
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); never null
     * @param abortHandler the infrastructure-abort protocol (task 5.3); never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed through to {@code
     *     abortHandler}; positive
     * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once a resume bootstrap
     *     succeeds, matching {@link GitResumeRunner}'s own key
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
     *     environment variable names (design D17, NFR-S1 of add-tracker-port), threaded down to
     *     every {@link TakeEngineExecution} this disposition eventually constructs; never null
     * @param heartbeat the instance heartbeat lifecycle registered/unregistered around the claimed
     *     run (task 6.1 of add-claim-heartbeat, FR1); {@link ClaimBeat#NONE} when no beat runs
     * @param takeoverFlag whether {@code --takeover} authorized a headless {@code Working} takeover
     *     (task 6.2, FR6): bypasses the {@code confirmation} seam
     * @param confirmation the pre-claim takeover-confirmation seam (task 6.2, FR6, design D9); never null
     * @param clock the run's clock, used only to render the display-only last-beat age in the
     *     takeover facts (design D9); never null
     * @param claimLossFlag the per-run heartbeat claim-loss flag (task 6.3, FR8 of
     *     add-claim-heartbeat), threaded down to every {@link TakeEngineExecution} this disposition
     *     constructs so the round boundary reacts to a beat-detected loss as a revocation; never null
     */
    TakeDisposition(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            boolean takeoverFlag,
            TakeoverConfirmation confirmation,
            Clock clock,
            ClaimLossFlag claimLossFlag,
            ContainerTakeSupport containerTakeSupport) {
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
                containerTakeSupport);
        this.takeover = new TakeTakeover(claimAndWork, confirmation, takeoverFlag, clock);
    }

    /**
     * The heartbeat- and takeover-free construction used where neither a beat nor an explicit
     * takeover runs (the {@code Ready}/{@code AwaitingHuman}/{@code Finished}/{@code Gone}
     * disposition unit specs): delegates with {@link ClaimBeat#NONE}, no {@code --takeover}, the
     * {@link TakeoverConfirmation#UNAVAILABLE} headless default on a system clock, and a fresh empty
     * {@link ClaimLossFlag} that never trips, so those call sites are unaffected by the added seams.
     */
    TakeDisposition(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            List<String> credentialEnvVarsToScrub) {
        this(
                assembly,
                git,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                taskIdMdcKey,
                credentialEnvVarsToScrub,
                ClaimBeat.NONE,
                false,
                TakeoverConfirmation.UNAVAILABLE,
                Clock.systemUTC(),
                new ClaimLossFlag(),
                ContainerTakeSupport.hostOnly());
    }

    /**
     * Dispatches on {@code trackerTask.state()} per the explicit-mode disposition matrix (FR9, UX2).
     *
     * <p>Implements FR9, UX2, D2, D3 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated outside a task worktree
     * @param base the {@code --base} override for a fresh claim, or {@code null}; ignored when
     *     resuming an existing branch (D4: a tracker task always starts at the pipeline's first
     *     stage on a fresh claim, only {@code --base} chooses where that start commit is)
     * @param definition the loaded pipeline the run advances through; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @param discardWork {@code --discard-work}: true discards an interrupted round's leftovers
     *     instead of salvaging them, meaningful only when resuming
     * @param trackerTask the already-fetched task fact set {@code take <ref>} is acting on; never
     *     null
     * @param tracker the tracker port; never null
     * @param instanceId this factory instance's identity; never null
     * @return the {@link TakeResult} of the disposition
     */
    public TakeResult dispose(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        TaskRef ref = trackerTask.ref();
        return switch (trackerTask.state()) {
            case TrackerTaskState.Ready ignored when trackerTask.finished() -> refuseFinished(ref, tracker);
            case TrackerTaskState.Ready ignored ->
                claimAndWork.claimAndWork(
                        cloneDir, base, definition, interactiveMode, discardWork, trackerTask, tracker, instanceId);
            case TrackerTaskState.Working working ->
                takeover.take(
                        cloneDir,
                        base,
                        definition,
                        interactiveMode,
                        discardWork,
                        trackerTask,
                        tracker,
                        instanceId,
                        working.holder());
            case TrackerTaskState.AwaitingHuman awaitingHuman -> refuseParked(awaitingHuman.reason());
            case TrackerTaskState.Finished ignored ->
                new TakeResult.Skipped("Task " + ref.id() + " is already done (Finished) — nothing to take.");
            case TrackerTaskState.Gone ignored ->
                new TakeResult.Skipped("Task " + ref.id() + " is closed or does not exist.");
        };
    }

    /** A reopened-finished {@code Ready} task: refuses via the decline protocol, never claiming (FR5). */
    private static TakeResult refuseFinished(TaskRef ref, Tracker tracker) {
        log.info("declining reopened finished task {} refused under an explicit take <ref> mandate", ref.id());
        tracker.declineFinished(ref, DeclineFinishedMessage.forTask(ref));
        return new TakeResult.Skipped("Task " + ref.id() + " is already finished — nothing to take.");
    }

    private static TakeResult refuseParked(ParkReason reason) {
        String returnPath =
                switch (reason) {
                    case ESCALATION ->
                        "A pending question is recorded; reply in the tracker and move the task back to ready.";
                    case CHECKPOINT ->
                        "A manual checkpoint is recorded; move the task back to ready to continue past it.";
                    case INFRA ->
                        "An environment or pipeline problem is recorded; fix it, then move the task back to ready"
                                + " to retry.";
                };
        // The original park report text is not retrievable here (the Tracker port exposes no "read
        // report" operation), so UX2/FR9 are met by naming the reason and return path honestly.
        return new TakeResult.Skipped("Task is parked awaiting a human (" + reason + "). " + returnPath);
    }
}
