package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

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
 * <p>Implements FR10 of add-git-workflow.
 */
public final class WorktreeSalvage {

    private final GitProcessRunner runner;
    private final Path worktreeRoot;

    public WorktreeSalvage(GitProcessRunner runner, Path worktreeRoot) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
    }

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
    public void salvage(String taskId) {
        if (!hasLeftovers()) {
            return;
        }
        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitSalvageFailedException(taskId, "git add -A", add.stderr());
        }
        GitCommandResult commit = runner.run(worktreeRoot, "commit", "-m", ServiceCommitMessages.salvage());
        if (commit.exitCode() != 0) {
            throw new GitSalvageFailedException(taskId, "git commit", commit.stderr());
        }
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
