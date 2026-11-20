package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorOutcomeClassifier;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;

/**
 * The git-mode host trigger of the round snapshot (design D22 of migrate-verify-to-executor-contract):
 * a decoration of the run's stage chain that, after the delegate returns {@code completed}, closes
 * the round's gnome work in a snapshot commit before the engine verifies it, through the one {@link
 * RoundSnapshot} step over the host worktree ({@link WorktreeSnapshot}) and the run's cell.
 *
 * <p>Only a completed round with no question is snapshotted — the round the engine's classifier calls
 * {@linkplain ExecutorOutcomeClassifier#verifiable verifiable}, asked rather than read here (G4 of
 * define-executor-contract). A round that ended {@code failed} or
 * {@code pending}, or that asked a decision, keeps its record commit as its one commit: a resume
 * would otherwise re-verify a failed round, or verify a round whose question the host's per-round
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
 * <p>Implements FR19 of migrate-verify-to-executor-contract.
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
        if (!ExecutorOutcomeClassifier.verifiable(result)) {
            return result;
        }
        var workspace = (DirectoryWorkspace) request.workspace();
        String taskId = request.context().taskId();
        new RoundSnapshot(new WorktreeSnapshot(runner, workspace.root(), taskId, epochs), rounds)
                .take(taskId, request.stage().name(), request.attempt(), RoundClose.VERIFIABLE);
        return result;
    }
}
