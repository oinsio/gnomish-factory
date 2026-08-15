package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence;
import com.github.oinsio.gnomish.app.take.RevocationDetectedException;
import com.github.oinsio.gnomish.app.take.RevocationHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared "run the engine exactly once, no dialog" tail both {@link
 * TakeResumeRunner#resumeWithoutDecision} and {@link TakeResumeRunner#resumeWithDecision} call
 * into (design D2, D3): assembles the same {@code EnginePorts} bundle manual-run uses via {@link
 * RunAssembly#assemble}, but drives {@link Engine#run} directly instead of {@link
 * RunnerOutcomeLoop} — {@code take} must behave identically with or without a TTY (design D12),
 * so no in-run console dialog is ever opened for {@code Escalated}/{@code Paused}.
 *
 * <p>{@link Engine#run} returns {@code Aborted} as a plain value, never as a thrown exception —
 * {@link AbortedException} is a {@link RunnerOutcomeLoop}-only wrapper decision, not something the
 * bare engine call produces (verified by reading {@link Engine#run}'s full body: every arm of its
 * exhaustive {@code TaskOutcome}-producing switch returns a value, nothing throws on {@code
 * Aborted}). So an {@code Aborted} return here is routed to {@link AbortHandler} — task 5.3's
 * infrastructure-abort protocol — with fresh {@link
 * com.github.oinsio.gnomish.app.port.tracker.AbortFacts} fetched from the tracker for the K-fuse
 * decision.
 *
 * <p>A revocation is detected by querying {@link RevocationCheckingAttemptPersistence#revocation()}
 * on the wrapped persistence immediately after {@link Engine#run} returns — NOT by catching {@link
 * RevocationDetectedException}, which never reaches this method: {@code AttemptJournal#commit}
 * inside the engine catches it (like any {@code RuntimeException} from {@code persist}) and turns
 * it into a {@code TaskOutcome.Aborted} by {@code AttemptPersistence}'s own documented contract.
 * That {@code Aborted} is therefore checked as a false signal — {@link
 * RevocationCheckingAttemptPersistence#revocation()} is consulted first, regardless of which
 * {@link TaskOutcome} came back, and only when it is empty does the
 * normal {@code Aborted}/{@code Completed}/{@code Paused}/{@code Escalated} dispatch below apply.
 * When a revocation is recorded, control goes to {@link RevocationHandler} instead; {@link
 * GitOutcomeRecorder#recordAndCleanUp} is deliberately NOT called on that path (FR15: revocation
 * leaves the tracker state and cleanup untouched — {@link RevocationHandler} already performs its
 * own salvage/push protocol).
 *
 * <p>Implements FR9, FR12, D2, D3 of add-tracker-port.
 *
 * @param assembly builds the {@code EnginePorts} bundle for the run; never null
 * @param git the task-git capability set: the run's repository and round persistence, the
 *     revocation protocol's best-effort push, and salvage plus terminal cleanup; never null
 * @param cloneDir the project clone the task worktree was materialized under; never null
 * @param worktreesRoot the worktrees root the task's repository is rooted under; never null
 * @param abortHandler the infrastructure-abort protocol (task 5.3), applied when the engine
 *     returns {@code Aborted}; never null
 * @param abortThreshold the configured abort-fuse threshold (K) passed to {@code
 *     abortHandler}; positive
 * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
 *     environment variable names (design D17, NFR-S1 of add-tracker-port), threaded into
 *     {@link RunAssembly#assemble}; never null
 * @param claimLossFlag the per-run heartbeat claim-loss flag consulted at each round boundary by
 *     {@link RevocationCheckingAttemptPersistence} in addition to its {@code fetchTask} check
 *     (FR8, design D7 of add-claim-heartbeat): a set flag means a beat already proved the claim
 *     gone, so the boundary reacts as a revocation; never null (an empty flag never trips)
 */
record TakeEngineExecution(
        RunAssembly assembly,
        TaskGit git,
        Path cloneDir,
        Path worktreesRoot,
        AbortHandler abortHandler,
        int abortThreshold,
        List<String> credentialEnvVarsToScrub,
        ClaimLossFlag claimLossFlag) {

    /**
     * Runs the engine exactly once against {@code context}/{@code state}, wrapping the round
     * persistence with {@link RevocationCheckingAttemptPersistence} and recording the terminal
     * outcome through {@link GitOutcomeRecorder} — unless the run was revoked mid-flight, in which
     * case {@link RevocationHandler} owns the git-side cleanup instead, or the run aborted, in
     * which case {@link AbortHandler} owns the tracker-side protocol. Every non-aborted, non-revoked
     * outcome is carried through to a real tracker call by an exhaustive switch: a fresh {@code
     * Escalated} outcome is parked through {@link TakeEscalationExit} (task 5.8, FR13, D12), a fresh
     * {@code Completed} outcome is finished through {@link TakeFinishReport} (task 5.11, FR18, D11),
     * and a fresh {@code Paused} outcome is parked as {@code AwaitingHuman(CHECKPOINT)} through
     * {@link TakePauseExit} (FR13, FR18, D12) — so a manual checkpoint that exits with code 11
     * leaves the task {@code AwaitingHuman} in the tracker, not stuck {@code Working}.
     *
     * <p>Implements FR9, FR12, FR13, FR18, D2, D3, D11, D12 of add-tracker-port.
     *
     * @param definition the pipeline the run advances through; never null
     * @param bootstrap the located/materialized bundle the worktree and taskId are read from
     * @param context the task context to run with — the caller's choice of original or
     *     decision-appended context
     * @param state the state to run with — the caller's choice of unchanged or attempt-reset state
     * @param interactiveMode which role(s) use the interactive adapter
     * @param tracker the tracker port, for the revocation check and, on an {@code Aborted}
     *     outcome, the abort-facts fetch
     * @param ref the task's tracker identity
     * @param instanceId this factory instance's identity
     * @return the {@link TakeResult} the terminal outcome maps to
     */
    TakeResult run(
            PipelineDefinition definition,
            ResumeBootstrap bootstrap,
            TaskContext context,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        Path worktree = bootstrap.worktreePath();
        String taskId = bootstrap.taskId();
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        var delegate = git.store().attemptPersistence(worktree, taskId);
        var persistence = new RevocationCheckingAttemptPersistence(delegate, tracker, ref, instanceId, claimLossFlag);
        var workspace = new DirectoryWorkspace(worktree);
        var assembled = assembly.assemble(
                definition, context, state, interactiveMode, persistence, credentialEnvVarsToScrub, cloneDir);

        TaskOutcome outcome = new Engine().run(definition, context, state, workspace, assembled.ports());

        var revocation = persistence.revocation();
        if (revocation.isPresent()) {
            RevocationDetectedException revoked = revocation.get();
            var handler = new RevocationHandler(tracker, git.worktrees().salvage(worktree), git.branches());
            return handler.handle(
                    ref, taskId, outcome.finalState(), worktree, bootstrap.branchName(), reasonFor(revoked));
        }

        GitOutcomeRecorder.recordAndCleanUp(git.worktrees(), taskRepository, cloneDir, worktree, taskId, outcome);
        // The durable "tracker-write pending" marker recordOutcome set for a park (Escalated/Paused)
        // is cleared only once its git-unfenced tracker write confirms — clearTerminalMarker is the
        // onConfirmed callback the park exits run (FR10, D10 of add-claim-heartbeat). A give-up leaves
        // the marker set for reconcile-on-resume; a finish uses no marker (cleanup-detection reconcile).
        var retry = TerminalWriteRetry.system();
        Runnable clearMarker = () -> taskRepository.confirmTerminalWrite(taskId);
        return switch (outcome) {
            case TaskOutcome.Aborted aborted -> {
                var facts = tracker.fetchTask(ref).abortFacts();
                yield abortHandler.handle(
                        ref, aborted.finalState(), aborted.cause(), facts, abortThreshold, instanceId);
            }
            case TaskOutcome.Escalated escalated ->
                TakeEscalationExit.exit(escalated, tracker, ref, instanceId, retry, clearMarker);
            case TaskOutcome.Completed completed ->
                TakeFinishReport.finish(completed, context, bootstrap.branchName(), tracker, ref, instanceId, retry);
            case TaskOutcome.Paused paused ->
                TakePauseExit.finish(
                        paused, context, bootstrap.branchName(), tracker, ref, instanceId, retry, clearMarker);
        };
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — the
    // null branch is provably unreachable: RevocationDetectedException's sole constructor always
    // calls super(String) with a non-null, non-blank message built from its taskId/reason
    // parameters (see its class body), so getMessage() can never be null here. Isolated to its own
    // method so this defensive-but-dead branch has nowhere for a mutant to hide as a false
    // SURVIVED against the rest of this class's revocation-handling logic, which
    // TakeResumeRunnerRevocationSpec already covers.
    @DoNotMutate
    private static String reasonFor(RevocationDetectedException revoked) {
        String message = revoked.getMessage();
        return message == null ? "revocation detected" : message;
    }
}
