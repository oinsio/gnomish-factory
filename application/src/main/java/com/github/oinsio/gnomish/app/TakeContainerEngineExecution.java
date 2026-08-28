package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.FinishTransition;
import com.github.oinsio.gnomish.app.take.ParkTransition;
import com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence;
import com.github.oinsio.gnomish.app.take.RevocationDetectedException;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The container-mode counterpart of {@link TakeEngineExecution} (FR1 of add-serve-sandbox-
 * lifecycle): runs the engine exactly once against a {@link SandboxRunSupport} bundle instead of
 * a host worktree, and settles the D19 terminal boundary itself. {@code take} drives {@link
 * Engine#run} directly (no {@code RunnerOutcomeLoop}, mirroring {@link TakeEngineExecution}'s own
 * reasoning: identical behavior with or without a TTY), so unlike {@link ContainerTerminalDrive}
 * — which leans on the outcome loop's own dialog-driven looping to decide when to dispose — this
 * class branches on the actual returned {@link TaskOutcome} itself: {@code Completed} disposes;
 * every other outcome keeps the environment stopped, and {@code Aborted} additionally records on
 * the last harvested tip before being kept (D19).
 *
 * <p>Revocation is handled inline rather than through {@link
 * com.github.oinsio.gnomish.app.take.RevocationHandler} (FR15 of add-tracker-port): that class's
 * salvage/push ports are host-shaped (against a worktree path), whereas the sandboxed protocol is
 * exactly {@link SandboxRunSupport#revocationSalvageAndPush} — in-box commit plus a
 * cloneDir-rooted push, no worktree involved. A revoked box is left exactly as the claim loss
 * found it (running or not): the next sweep tick classifies it unowned and stops it, per design
 * D3 — no separate disposal here.
 *
 * <p>No sweep-lifecycle pass runs here, unlike {@link ContainerTerminalDrive}'s {@code run} path
 * (FR6, NFR-P1 of add-serve-sandbox-lifecycle). The tracked entry points own their own pass: {@code
 * take} sweeps once at startup with the heartbeat's real liveness verdict ({@link
 * TakeCommandSupport#sweepSandboxLifecycle}), and {@code serve} sweeps on its periodic tick, off
 * the slot path by requirement. A pass here would be a third one — on the slot thread, and blind:
 * a claim-time pass carries no liveness verdict, and a no-verdict pass skips every {@code tracked}
 * object by construction, so the only objects it could ever act on are the {@code manual} ones of
 * somebody else's {@code gnomish run} session — acted on outside the daemon's ledger and vitals.
 *
 * <p>A park here records its outcome on the branch and settles its marker exactly as host mode does
 * (FR10, design D12 of harden-task-branch-contract). Both halves used to be missing: the
 * factory-side bare-object repository implemented only the plain {@code TaskRepository}, so a
 * container park wrote nothing at all and every later resume re-parked the task against a branch
 * that had no park on it. The repository is a {@code TaskLifecycleStore} now, and this class drives
 * its {@code recordPark}/{@code confirmTerminalWrite} pair through the shared protocol.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, FR13, FR15, FR18, D2, D3, D19 of
 * add-tracker-port and add-sandbox-core.
 */
record TakeContainerEngineExecution(
        RunAssembly assembly,
        AbortHandler abortHandler,
        int abortThreshold,
        List<String> credentialEnvVarsToScrub,
        ClaimLossFlag claimLossFlag,
        Path cloneDir) {

    TakeResult run(
            SandboxRunSupport support,
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            String taskId,
            @Nullable PendingVerification pending) {
        var persistence = new RevocationCheckingAttemptPersistence(
                support.persistence(), tracker, ref, instanceId, claimLossFlag);
        var assembled = assembly.withSandbox(support.pieces(pending))
                .assemble(definition, context, state, interactiveMode, persistence, credentialEnvVarsToScrub, cloneDir);

        support.restoreDenialCursor();

        TaskOutcome outcome = new Engine().run(definition, context, state, support.workspace(), assembled.ports());

        var revocation = persistence.revocation();
        if (revocation.isPresent()) {
            support.revocationSalvageAndPush(taskId);
            String note = "Work stopped: " + RevocationDetectedException.reasonFor(revocation.get())
                    + ". Uncommitted work was"
                    + " salvage-committed and the branch left in place for whoever resumes this task.";
            tracker.postNote(ref, note);
            tracker.release(ref);
            return new TakeResult.Revoked(outcome.finalState(), note);
        }

        settleTerminalBoundary(support, outcome);

        var retry = TerminalWriteRetry.system();
        String branchName = TaskIdSanitizer.branchName(taskId);
        // The park's intent is recorded here, not in settleTerminalBoundary: that method only settles
        // the box (kept stopped), and the outcome commit belongs to the protocol that follows it
        // (FR10, D12 of harden-task-branch-contract). No delivery fence exists in container mode —
        // the recording push is the repository decorator's, best-effort as every container-mode push
        // is — so the verdict handed to the report is Delivered. The completion's intent, by
        // contrast, was recorded inside completeAndDispose, which D19 orders before this point; what
        // remains of it is the cleanup commit, the destructive last step behind the confirmed finish.
        var park = new ParkTransition.Fresh(
                () -> {
                    support.recordPark(outcome);
                    return new ParkDeliveryVerdict.Delivered();
                },
                support::confirmTerminalWrite);
        var finish = new FinishTransition.Fresh(() -> {}, support::finishCleanup);
        return TakeOutcomeDispatch.dispatch(
                outcome,
                context,
                branchName,
                tracker,
                ref,
                instanceId,
                retry,
                park,
                abortHandler,
                abortThreshold,
                finish);
    }

    /**
     * D19: {@code Completed} disposes; every other outcome keeps the box stopped for salvage/resume.
     * Both arms carry the terminal-boundary remote reconciliation (FR3 of fix-lifecycle-push) inside
     * the support bundle's own dispose/keep methods, so a park's branch tip gets its last delivery
     * attempt here without this switch naming git at all.
     */
    private static void settleTerminalBoundary(SandboxRunSupport support, TaskOutcome outcome) {
        switch (outcome) {
            case TaskOutcome.Completed completed -> support.completeAndDispose(completed.finalState());
            case TaskOutcome.Aborted aborted -> {
                support.recordAborted(aborted);
                support.keepStopped();
            }
            case TaskOutcome.Escalated ignored -> support.keepStopped();
            case TaskOutcome.Paused ignored -> support.keepStopped();
        }
    }
}
