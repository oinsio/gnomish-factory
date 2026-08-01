package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.BranchLocation;
import com.github.oinsio.gnomish.adapter.git.DivergedBranchException;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.TaskBranchLocator;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeCrashAbort;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The shared "claim, then either create or resume the branch" logic behind both take entry points
 * (FR9, FR10, D3): {@code tracker.claim(ref, instanceId)}, and on {@link ClaimResult.Acquired}
 * checks via {@link TaskBranchLocator} whether a branch already exists for the task — if not,
 * delegates to {@link TakeFreshClaim#claim} (first-ever claim); if one exists, delegates to {@link
 * TakeDispositionResume#resumeExisting} (resume from the recorded outcome). On {@link
 * ClaimResult.Held} returns {@link #refuseHeld}, naming the current holder.
 *
 * <p>Extracted out of {@link TakeDisposition} (task 5.9) so bare auto mode (task 5.10) can reuse
 * the identical claim-and-dispatch sequence without duplicating it; explicit mode's {@link
 * TakeDisposition#dispose} still delegates here for its {@code Ready} case. Split purely to respect
 * the file-size guidance (`.claude/rules/process-invariants.md`).
 *
 * <p>The class and {@link #dispatchAfterClaim} are {@code public} — the only members widened
 * beyond this package's usual package-private convention (see the sibling {@code Take*} claim/
 * resume helpers) — so {@code com.github.oinsio.gnomish.app.serve.TakeSlotRunner} (task 4.3 of
 * add-factory-serve) can invoke the identical "already-claimed, dispatch the take cycle" sequence
 * a {@code serve} slot needs, without duplicating this logic or relocating scheduler code out of
 * its established {@code app.serve} package. {@link #claimAndWork} stays package-private: no
 * caller outside {@code app} claims fresh itself.
 *
 * <p>Implements FR9, FR10, D3 of add-tracker-port. Implements FR1, M2 of add-factory-serve.
 */
public final class TakeClaimAndWork {

    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;
    private final AbortHandler abortHandler;
    private final int abortThreshold;
    private final List<String> credentialEnvVarsToScrub;
    private final TakeDispositionResume dispositionResume;
    private final ClaimBeat heartbeat;
    private final ClaimLossFlag claimLossFlag;
    private final TakeCrashAbort crashAbort;

    TakeClaimAndWork(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            TakeDispositionResume dispositionResume,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
        this.abortHandler = abortHandler;
        this.abortThreshold = abortThreshold;
        this.credentialEnvVarsToScrub = credentialEnvVarsToScrub;
        this.dispositionResume = dispositionResume;
        this.heartbeat = heartbeat;
        this.claimLossFlag = claimLossFlag;
        this.crashAbort = new TakeCrashAbort(abortHandler, abortThreshold);
    }

    /**
     * Builds a ready-to-use {@link TakeClaimAndWork} from just the ingredients {@link
     * TakeBareAuto} itself takes, wiring the same {@link TakeResumeRunner}/{@link
     * TakeDispositionResume}/{@link TakeDecisionResume} chain {@link TakeBareAuto}'s constructor
     * builds inline. Exists so a caller outside this package (only {@code
     * com.github.oinsio.gnomish.app.serve.TakeSlotRunner}, task 4.3 of add-factory-serve) can get
     * a working instance without those three package-private resume-machinery classes needing to
     * be widened too — this factory method is the sole crossing point.
     *
     * <p>Implements FR1, M2 of add-factory-serve.
     */
    public static TakeClaimAndWork forSlot(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag) {
        var resumeRunner = new TakeResumeRunner(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag);
        var dispositionResume =
                new TakeDispositionResume(resumeRunner, new TakeDecisionResume(resumeRunner), worktreesRoot);
        return new TakeClaimAndWork(
                assembly,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                dispositionResume,
                heartbeat,
                claimLossFlag);
    }

    /**
     * Claims {@code trackerTask.ref()} and, on success, either starts a fresh claim or resumes an
     * existing branch (see class javadoc). On a lost claim race, returns {@link #refuseHeld}.
     *
     * <p>Callers that already hold a successful {@link ClaimResult.Acquired} for this ref (bare
     * auto mode, task 5.10 — it claims once while walking the eligible queue) should call {@link
     * #dispatchAfterClaim} directly instead, to avoid claiming twice.
     *
     * <p>Implements FR9, FR10, D3 of add-tracker-port.
     */
    TakeResult claimAndWork(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        var ref = trackerTask.ref();
        ClaimResult claim = tracker.claim(ref, instanceId.value());
        if (claim instanceof ClaimResult.Held(String otherInstance)) {
            return refuseHeld(otherInstance);
        }
        return dispatchAfterClaim(
                cloneDir, base, definition, interactiveMode, discardWork, trackerTask, tracker, instanceId);
    }

    /**
     * Dispatches a fresh claim or an existing-branch resume for {@code trackerTask.ref()},
     * assuming the caller already holds a successful claim on it (see {@link #claimAndWork}'s
     * javadoc for when to call this directly instead).
     *
     * <p>Because a claim is already held here, any uncaught {@link RuntimeException} of the work
     * this dispatches — a fresh-claim git operation, a resume/salvage step, or a tracker write
     * itself — is a runner crash of a claimed run, so it funnels into the same best-effort abort
     * protocol as an engine {@code Aborted} via {@link TakeCrashAbort} (FR14 "Runner crash is an
     * abort", D16 "an uncaught exception runs the abort protocol and exits 12 or 13, never a bare
     * 1"). Deliberate, dedicated-exit-code control flow is exempt and rethrown unchanged: {@link
     * UsageException} keeps exit 2 and {@link DivergedBranchException} keeps exit 5 (D16: codes
     * shared with {@code run} keep their meaning). A claim that never succeeds is a pre-claim
     * failure handled one layer up (exit 1) and never reaches this method.
     *
     * <p>The instance heartbeat lifecycle is anchored here (task 6.1 of add-claim-heartbeat, FR1):
     * this is the single choke point every claim-holding path reaches — explicit {@code Ready} via
     * {@link #claimAndWork}, explicit resume, and bare-auto's own pre-held claim — so {@link
     * ClaimBeat#register} is called the instant a claim is held (starting the beat thread on the
     * first claim) and {@link ClaimBeat#unregister} in a {@code finally}, stopping the beats at any
     * terminal result, exception, or crash-abort. A path that never holds a claim (a lost race,
     * an empty queue) never reaches this method, so it never beats.
     *
     * <p>Implements FR9, FR10, FR14, D3, D16 of add-tracker-port; FR1 of add-claim-heartbeat; FR1
     * of add-factory-serve.
     */
    public TakeResult dispatchAfterClaim(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        TaskRef ref = trackerTask.ref();
        heartbeat.register(ref);
        try {
            return locateAndWork(
                    cloneDir, base, definition, interactiveMode, discardWork, trackerTask, tracker, instanceId);
        } catch (UsageException | DivergedBranchException deliberate) {
            throw deliberate;
        } catch (RuntimeException crash) {
            return crashAbort.onCrash(definition, trackerTask, tracker, instanceId, crash);
        } finally {
            heartbeat.unregister(ref);
        }
    }

    private TakeResult locateAndWork(
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        var ref = trackerTask.ref();
        GitProcessRunner runner = new GitProcessRunner();
        String taskId = trackerTask.snapshot().id();
        BranchLocation location = new TaskBranchLocator(runner).locate(cloneDir, taskId);
        if (location instanceof BranchLocation.NotFound) {
            return TakeFreshClaim.claim(
                    assembly,
                    worktreesRoot,
                    abortHandler,
                    abortThreshold,
                    credentialEnvVarsToScrub,
                    cloneDir,
                    base,
                    definition,
                    interactiveMode,
                    trackerTask,
                    tracker,
                    instanceId,
                    claimLossFlag);
        }
        return dispositionResume.resumeExisting(
                cloneDir, definition, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
    }

    /**
     * Refuses a task already held by another instance (FR9, UX2), naming the holder.
     *
     * <p>Implements FR9, UX2 of add-tracker-port.
     */
    static TakeResult refuseHeld(String holder) {
        return new TakeResult.Skipped("Task is claimed by another instance (" + holder + ") — refusing to take it.");
    }
}
