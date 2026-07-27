package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The explicit-mode ({@code take <ref>}) disposition matrix (proposal FR9, UX2; design D2, D3):
 * given an already-fetched {@link TrackerTask}, dispatches per its {@link TrackerTaskState} —
 * {@code Ready} claims and works it (fresh or resumed), {@code AwaitingHuman}/{@code Working}
 * refuse without mutating the tracker, {@code Finished}/{@code Gone} skip. The operator mandate
 * overrides the readiness criterion and abort backoff (FR9) simply by never consulting either:
 * this class only reads the state {@code fetchTask} already reported.
 *
 * <p>Short-ref expansion (`42`, `#42`) is a later concern (task 5.14, not built here) — {@link
 * #dispose} takes an already-resolved {@link TaskRef}. Argument parsing and Spring wiring for the
 * {@code take} CLI surface belong to task 5.13; this class is the plain, constructor-injectable
 * entry point that command wiring calls into.
 *
 * <p>Implements FR9, UX2, D2, D3 of add-tracker-port.
 */
final class TakeDisposition {

    private final TakeClaimAndWork claimAndWork;

    /**
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
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
     */
    TakeDisposition(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            List<String> credentialEnvVarsToScrub) {
        var resumeRunner = new TakeResumeRunner(
                assembly, worktreesRoot, taskIdMdcKey, abortHandler, abortThreshold, credentialEnvVarsToScrub);
        var dispositionResume = new TakeDispositionResume(resumeRunner, new TakeDecisionResume(resumeRunner));
        this.claimAndWork = new TakeClaimAndWork(
                assembly, worktreesRoot, abortHandler, abortThreshold, credentialEnvVarsToScrub, dispositionResume);
    }

    /**
     * Dispatches on {@code trackerTask.state()} per the explicit-mode disposition matrix (FR9,
     * UX2).
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
            case TrackerTaskState.Ready ignored ->
                claimAndWork.claimAndWork(
                        cloneDir, base, definition, interactiveMode, discardWork, trackerTask, tracker, instanceId);
            case TrackerTaskState.Working working -> TakeClaimAndWork.refuseHeld(working.holder());
            case TrackerTaskState.AwaitingHuman awaitingHuman -> refuseParked(awaitingHuman.reason());
            case TrackerTaskState.Finished ignored ->
                new TakeResult.Skipped("Task " + ref.id() + " is already done (Finished) — nothing to take.");
            case TrackerTaskState.Gone ignored ->
                new TakeResult.Skipped("Task " + ref.id() + " is closed or does not exist.");
        };
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
        // The actual original park report text is not retrievable here: the Tracker port has no
        // "read current report" operation (fetchTask reports only state + holder/reason, never the
        // report body). Naming the reason and the return path honestly, without inventing report
        // content, is what UX2/FR9 require.
        return new TakeResult.Skipped("Task is parked awaiting a human (" + reason + "). " + returnPath);
    }
}
