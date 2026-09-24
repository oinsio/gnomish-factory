package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.app.take.AbortFuse;
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
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
 * <p>Kept in sync with {@link TakeEngineExecution}: both drive one engine run per claim through
 * the same {@code RunAssembly.assemble} contract, and in particular both hand it the task's
 * <em>law binding</em> — the repository and the commit its {@code .gnomish/} law and
 * external-check pin are read from (design D12 of add-base-ref-resolution) — as a constructor
 * argument taken from the claim, never derived per medium. A binding that changes on one side and not the other would make the
 * same task read different law in host and container mode.
 *
 * <p>Kept in sync with {@link com.github.oinsio.gnomish.app.take.RevocationHandler}: both must end
 * a revoked claim with the same stop note and the same two tracker writes — the "Work stopped:"
 * heading over the revocation reason taken through the comment plane's fenced shape, then {@code
 * postNote} followed by {@code release}, and never {@code park}, {@code recordAbort} or {@code
 * finish} (FR15 of add-tracker-port). Only the salvage mechanics differ by medium, which is why
 * this end calls {@link SandboxRunSupport#revocationSalvageAndPush} where the host end salvages
 * against a worktree path. The note is what a human reads on the thread of a task that stopped
 * without a verdict, so a wording or an exit that changed on one medium alone would make the two
 * modes report the same event differently.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, FR13, FR15, FR18, D2, D3, D19 of
 * add-tracker-port and add-sandbox-core.
 */
record TakeContainerEngineExecution(
        RunAssembly assembly,
        AbortFuse abortFuse,
        List<String> credentialEnvVarsToScrub,
        ClaimLossFlag claimLossFlag,
        LawBinding lawBinding) {

    /**
     * Runs the engine exactly once against {@code support} (see class javadoc).
     *
     * @param order the take order the run executes: the pipeline it advances through (the task's
     *     own law on a fresh claim, design D6 of introduce-take-order), the interactive mode, and
     *     the tracker, task identity and instance identity for the revocation check and the
     *     terminal write; never null
     * @param support the sandboxed-run bundle the run executes against; never null
     * @param context the task context to run with; never null
     * @param state the state to run with; never null
     * @param pending the interrupted verification to re-verify, or {@code null} for a normal run
     * @return the {@link TakeResult} the terminal outcome maps to
     */
    TakeResult run(
            TakeOrder order,
            SandboxRunSupport support,
            TaskContext context,
            TaskState state,
            @Nullable PendingVerification pending) {
        PipelineDefinition definition = order.run().definition();
        var persistence = new RevocationCheckingAttemptPersistence(
                support.persistence(), order.tracker(), order.ref(), order.instanceId(), claimLossFlag);
        var assembled = assembly.withSandbox(support.pieces(pending))
                .assemble(order.run(), context, state, persistence, credentialEnvVarsToScrub, lawBinding);

        support.restoreDenials();

        TaskOutcome outcome = new Engine().run(definition, context, state, support.workspace(), assembled.ports());

        var revocation = persistence.revocation();
        if (revocation.isPresent()) {
            support.revocationSalvageAndPush(order.taskId());
            // The reason names what the tracker held — the new claim's holder, a closure reason —
            // so it leaves through the comment plane at the write, with the factory's own sentences
            // outside the fence (design D7 of type-untrusted-text). The fenced shape, not the
            // inline one: the note is a heading over one capture, so the label describes the block
            // it spans truthfully (design D6, revised 2026-09-19).
            UntrustedText reason = UntrustedText.tracker(RevocationDetectedException.reasonFor(revocation.get()));
            String note = "Work stopped:\n" + reason.forComment()
                    + "\nUncommitted work was salvage-committed and the branch left in place for whoever resumes"
                    + " this task.";
            order.tracker().postNote(order.ref(), note);
            order.tracker().release(order.ref());
            return new TakeResult.Revoked(outcome.finalState(), UntrustedText.factory(note));
        }

        settleTerminalBoundary(support, outcome);

        var retry = TerminalWriteRetry.system();
        String branchName = TaskIdSanitizer.branchName(order.taskId());
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
                outcome, context, branchName, order, retry, park, abortFuse.handler(), abortFuse.threshold(), finish);
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
