package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import java.nio.file.Path

/**
 * The host round persistence for specs and seeding fixtures that persist rounds with no round
 * source in front of them (design D22 of migrate-verify-to-executor-contract). Production opens each
 * git-mode host round in the run's cell over the worktree tip it opens on ({@code
 * MidRoundPushRounds}), and {@link GitAttemptPersistence} bases the round's boundary on that cell;
 * this stand-in opens the cell over the worktree's {@code HEAD} at construction and again after each
 * round commit lands — exactly where the next round of a run would open — so a spec's commits between
 * two persists are the round's gnome range, as in a run.
 *
 * <p>The cell is exposed ({@link #rounds}) for a spec that snapshots a round the way the host stage
 * chain does, so the persist sees the snapshot as the round's base.
 */
final class HostRoundPersistence implements AttemptPersistence {

    final CurrentRound rounds = new CurrentRound()

    private final GitAttemptPersistence delegate
    private final HostBranchProbe probe

    private HostRoundPersistence(GitRunner runner, Path worktree, String taskId, ClaimEpochSource epochs) {
        this.probe = new HostBranchProbe(runner, worktree, TaskIdSanitizer.branchName(taskId))
        this.delegate = new GitAttemptPersistence(runner, worktree, taskId, epochs, rounds, new VirtualClock())
        openRound()
    }

    /**
     * @param runner the git runner the persistence and the tip reads go through
     * @param worktree the task worktree
     * @param taskId the tracker's taskId
     * @param epochs the tenure the round commits are stamped with
     * @return a persistence whose cell is opened over the worktree's current {@code HEAD}
     */
    static HostRoundPersistence over(GitRunner runner, Path worktree, String taskId, ClaimEpochSource epochs) {
        new HostRoundPersistence(runner, worktree, taskId, epochs)
    }

    /**
     * A run's round cell opened over {@code worktree}'s current {@code HEAD}, for a spec that hands
     * the cell to the store's own {@code attemptPersistence} with no round source in front of it.
     */
    static CurrentRound openedAtHead(GitRunner runner, Path worktree) {
        def cell = new CurrentRound()
        cell.open(RoundToken.of(VerifiedTip.required('HEAD', 'rev-parse', runner.run(worktree, 'rev-parse', 'HEAD'))))
        cell
    }

    /** Opens the next round over the worktree's current {@code HEAD}, as a run's round source would. */
    void openRound() {
        rounds.open(RoundToken.of(probe.currentHead()))
    }

    @Override
    void persist(String taskId, TaskState state, ToolTrace trace) {
        delegate.persist(taskId, state, trace)
        openRound()
    }

    @Override
    void persistPending(String taskId, TaskState state) {
        delegate.persistPending(taskId, state)
    }
}
