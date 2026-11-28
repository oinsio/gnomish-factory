package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorOutcomeClassifier;
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import org.jspecify.annotations.Nullable;

/**
 * The git-mode host trigger of the round snapshot (design D22 of migrate-verify-to-executor-contract):
 * a decoration of the run's stage chain that, after the delegate's {@code start} returns {@code
 * completed} or {@code pending}, or a poll returns {@code completed}, registers the round's gnome work
 * in a snapshot commit before the engine verifies or polls it, through the one {@link RoundSnapshot}
 * step over the host worktree ({@link WorktreeSnapshot}) and the run's cell.
 *
 * <p>A completed round with no question — the round the engine's classifier calls {@linkplain
 * ExecutorOutcomeClassifier#verifiable verifiable}, asked rather than read here (G4 of
 * define-executor-contract) — is snapshotted verifiable. A round that answered {@code pending} — a
 * stage round is a {@code transform} round — is snapshotted under the pending-round subject
 * ({@link RoundClose#PENDING}), the snapshot its pending round commit lands on, which no pickup
 * reads as a round to verify (design D11 of extract-agent-round-runner) — on the {@code start}'s
 * result only: a poll that answers {@code pending} continues the open round and takes no snapshot
 * (D11 as amended 2026-11-25), so only the terminal poll's verifiable result is snapshotted. A round
 * that ended {@code
 * failed}, or that asked a decision, keeps its record commit as its one commit: a resume would
 * otherwise re-verify a failed round, or verify a round whose question the host's per-round
 * decision file never put into the tree. It sits inside the resume re-verification decorator, so a
 * resumed round is not snapshotted again, and outside the executor dispatch, so both arms of a host
 * run are covered. The in-place run has neither this decoration nor a snapshot.
 *
 * <p>A snapshot the boundary verdict declined leaves the result as it was; the round's persist judges
 * the same tree and aborts the task. A snapshot that could not be committed throws, and the engine
 * turns the throw into the round's infrastructure failure.
 *
 * <p>The {@code Workspace} cast mirrors {@link MidRoundPushRounds}: host requests carry a {@link
 * DirectoryWorkspace} by construction.
 *
 * <p>Implements FR19 of migrate-verify-to-executor-contract; FR10 of extract-agent-round-runner.
 */
final class SnapshottingStageExecutor implements StageExecutor {

    private final StageExecutor delegate;
    private final GitRunner runner;
    private final ClaimEpochSource epochs;
    private final CurrentRound rounds;

    /**
     * @param delegate the run's stage chain; never null
     * @param runner the git subprocess runner; never null
     * @param epochs the tenure the snapshot commit is stamped with; never null
     * @param rounds the run's round cell; never null
     */
    SnapshottingStageExecutor(StageExecutor delegate, GitRunner runner, ClaimEpochSource epochs, CurrentRound rounds) {
        this.delegate = delegate;
        this.runner = runner;
        this.epochs = epochs;
        this.rounds = rounds;
    }

    @Override
    public ExecutionResult execute(Request request) {
        ExecutionResult result = delegate.execute(request);
        return snapshotted(request, result, closeOf(result));
    }

    /**
     * The poll of a pending {@code transform} round, forwarded down the chain: a verifiable terminal
     * result takes the round's snapshot, a {@code pending} one none (D11 as amended 2026-11-25).
     */
    @Override
    public ExecutionResult poll(Request request, PendingTurn turn) {
        ExecutionResult result = delegate.poll(request, turn);
        return snapshotted(
                request, result, ExecutorOutcomeClassifier.verifiable(result) ? RoundClose.VERIFIABLE : null);
    }

    private ExecutionResult snapshotted(Request request, ExecutionResult result, @Nullable RoundClose close) {
        if (close == null) {
            return result;
        }
        var workspace = (DirectoryWorkspace) request.workspace();
        String taskId = request.context().taskId();
        new RoundSnapshot(new WorktreeSnapshot(runner, workspace.root(), taskId, epochs), rounds)
                .take(taskId, request.stage().name(), request.attempt(), close);
        return result;
    }

    /** The snapshot's close for a {@code start}'s {@code result}, or {@code null} when it takes none. */
    private static @Nullable RoundClose closeOf(ExecutionResult result) {
        if (ExecutorOutcomeClassifier.verifiable(result)) {
            return RoundClose.VERIFIABLE;
        }
        return ExecutorOutcomeClassifier.pending(result).isPresent() ? RoundClose.PENDING : null;
    }
}
