package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles uncommitted leftovers found in a resumed task worktree after divergence
 * reconciliation (task 4.8) has already run — the "process died mid-round" case (FR8, FR10,
 * design D10): the worktree's {@code HEAD} is exactly the last round (or service) commit {@link
 * com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence#persist} left behind (see {@link
 * GitAttemptPersistence}, which commits atomically per round), so anything still dirty on top of
 * that {@code HEAD} is precisely the interrupted round's unsalvaged work.
 *
 * <p>Default (salvage, {@link #salvage}): the leftovers are committed as-is with {@link
 * ServiceCommitMessages#salvage()} — a plain {@code git add -A && git commit}, deliberately never
 * routed through {@link GitAttemptPersistence#persist} or {@link RoundBoundaryCheck}, since a
 * salvage commit is not a round and must not be counted in {@code state.json}'s attempts (FR10).
 * The next round's gnome sees the half-done work and the QC loop judges the result.
 *
 * <p>{@code --discard-work} ({@link #discard}): resets the worktree to {@code HEAD} — the last
 * recorded round's commit — discarding the leftovers entirely (tracked and untracked alike), so
 * the engine loop replays the interrupted round from a clean base. Never restarts the whole task:
 * only the one round in flight when the process died is replayed.
 *
 * <p>Either way, the factory's own {@code .gnomish-task/} files come from the branch tip and
 * never from the dirty worktree, per the shared ownership policy in {@link FactoryOwnedPaths}
 * (FR5, design D11 of harden-task-branch-contract) — the same policy the in-box {@link
 * EnvironmentSalvage} applies.
 *
 * <p>Kept in sync with {@link EnvironmentSalvage}: both must produce a salvage commit carrying the
 * claim-epoch trailer and restore factory-owned paths.
 *
 * <p>Implements FR10 of add-git-workflow; FR5 of harden-task-branch-contract.
 */
public record WorktreeSalvage(GitProcessRunner runner, Path worktreeRoot, ClaimEpochSource epochs)
        implements WorktreeSalvager {

    /**
     * True iff the worktree has any uncommitted change (staged, unstaged, or untracked) relative
     * to its current {@code HEAD} — {@code git status --porcelain}, scoped to this worktree.
     */
    public boolean hasLeftovers() {
        GitCommandResult status = runner.run(worktreeRoot, "status", "--porcelain");
        return !status.stdout().trim().isEmpty();
    }

    /**
     * Commits any uncommitted leftovers as-is with the fixed salvage message (FR10) — not a round,
     * never recorded in {@code state.json}. A no-op when {@link #hasLeftovers()} is false.
     *
     * @throws GitSalvageFailedException if staging or committing the leftovers fails
     */
    @Override
    public void salvage(String taskId) {
        restoreFactoryFiles();
        if (!hasLeftovers()) {
            return;
        }
        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitSalvageFailedException(taskId, "git add -A", add.stderr());
        }
        GitCommandResult commit = runner.run(
                worktreeRoot,
                "commit",
                "-m",
                ClaimEpochTrailer.stamp(
                        ServiceCommitMessages.salvage(), epochs.epochFor(taskId).orElse(null)));
        if (commit.exitCode() != 0) {
            throw new GitSalvageFailedException(taskId, "git commit", commit.stderr());
        }
    }

    /**
     * Puts every factory-owned {@code .gnomish-task/} path back the way the branch tip has it,
     * before anything is staged (FR5, design D11 of harden-task-branch-contract): tracked files
     * are checked out from {@code HEAD}, untracked ones are cleaned away, and the gnome-writable
     * {@code decisions/} subtree is excluded from both. A dying process may have left a partial or
     * stale {@code state.json} behind; salvage contributes the gnome's work files and never lets
     * that file overwrite what the recorded rounds say.
     *
     * <p>A no-op on a tip that carries no state directory at all — the {@code Completed} cleanup
     * commit removes it, and nothing there is the factory's to restore.
     */
    private void restoreFactoryFiles() {
        if (runner.run(worktreeRoot, "cat-file", "-e", "HEAD:" + FactoryOwnedPaths.STATE_DIR)
                        .exitCode()
                != 0) {
            return;
        }
        runner.run(worktreeRoot, args("checkout", "HEAD", "--"));
        runner.run(worktreeRoot, args("clean", "-fd", "--"));
    }

    /** {@code prefix} followed by the factory-owned pathspec, as one argv array. */
    private static String[] args(String... prefix) {
        List<String> argv = new ArrayList<>(List.of(prefix));
        argv.addAll(FactoryOwnedPaths.pathspec());
        return argv.toArray(new String[0]);
    }

    /**
     * Resets the worktree to its current {@code HEAD} (the last recorded round's commit),
     * discarding any uncommitted leftovers — tracked changes via {@code reset --hard} and
     * untracked files via {@code clean -fd}. A no-op when {@link #hasLeftovers()} is false.
     */
    public void discard() {
        if (!hasLeftovers()) {
            return;
        }
        runner.run(worktreeRoot, "reset", "--hard", "HEAD");
        runner.run(worktreeRoot, "clean", "-fd");
    }
}
