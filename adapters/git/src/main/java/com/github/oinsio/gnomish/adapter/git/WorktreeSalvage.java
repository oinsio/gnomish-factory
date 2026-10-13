package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException;
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles uncommitted leftovers found in a resumed task worktree after divergence
 * reconciliation (task 4.8) has already run — the "process died mid-round" case (FR8, FR10,
 * design D10): the worktree's {@code HEAD} is exactly the last round (or service) commit {@link
 * com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence#persist} left behind (see {@link
 * GitAttemptPersistence}, which commits atomically per round), so anything still dirty on top of
 * that {@code HEAD} is precisely the interrupted round's unsalvaged work.
 *
 * <p>Default (salvage, {@link #salvage}): the leftovers are committed as-is with {@link
 * ServiceCommitMessages#salvage()} — {@code git add -A}, then a closing commit landed with its
 * register in one ref transaction ({@link WorktreeClosingCommit}), deliberately never routed
 * through {@link GitAttemptPersistence#persist}, since a salvage commit is not a round and must not
 * be counted in {@code state.json}'s attempts (FR10).
 * The next round's gnome sees the half-done work and the QC loop judges the result.
 *
 * <p>{@code --discard-work} ({@link #discard}): resets the worktree to {@code HEAD} — the last
 * recorded round's commit — discarding the leftovers entirely (tracked and untracked alike), so
 * the engine loop replays the interrupted round from a clean base. Never restarts the whole task:
 * only the one round in flight when the process died is replayed.
 *
 * <p>Either way, the factory's own {@code .gnomish-task/} files come from the registered commit
 * and never from the dirty worktree or a {@code HEAD} the gnome committed above the register, per
 * the shared ownership policy in {@link FactoryOwnedPaths} (FR5, design D11 of
 * harden-task-branch-contract; design D18 of tighten-spec-contract) — restored by {@link
 * EnvelopeRestore}, the owner every host closing write uses.
 *
 * <p>Kept in sync with {@link EnvironmentSalvage}: both must produce a salvage commit carrying the
 * claim-epoch trailer, restore the factory-owned paths before staging — here from the registered
 * commit; there from the box's {@code HEAD}, with the admission refusing any owned path the gnome's
 * commits changed above the register — and, past the guard that tolerates a base with no state
 * directory, FAIL the salvage when that restore fails, rather than letting the working copy's
 * factory files ride into the commit. Their degrade paths are symmetric too: a discard that cannot
 * reach or reset its working copy leaves the leftovers in place, and both ends say so at WARN (FR5
 * of harden-logging-observability). And the salvage commit is a closing commit in both media,
 * landed with its register in one ref transaction only when it was made (design D13 of
 * tighten-spec-contract): this end through {@link WorktreeClosingCommit}, the in-box end through
 * the admission's transaction. A salvage that made no commit registers nothing at either end, so a
 * tip the gnome wrote is never registered in its place.
 *
 * <p>Implements FR10 of add-git-workflow; FR5 of harden-task-branch-contract; FR13 of
 * tighten-spec-contract.
 */
public record WorktreeSalvage(GitRunner runner, Path worktreeRoot, ClaimEpochSource epochs)
        implements WorktreeSalvager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeSalvage.class);

    /**
     * True iff the worktree has any uncommitted change (staged, unstaged, or untracked) relative
     * to its current {@code HEAD} — {@code git status --porcelain}, scoped to this worktree.
     */
    public boolean hasLeftovers() {
        GitCommandResult status = runner.run(worktreeRoot, "status", "--porcelain");
        return !status.stdout().isBlank();
    }

    /**
     * Commits any uncommitted leftovers as-is with the fixed salvage message (FR10) — not a round,
     * never recorded in {@code state.json}. A no-op when {@link #hasLeftovers()} is false.
     *
     * @throws GitSalvageFailedException if restoring the factory-owned paths, staging or landing
     *     the salvage commit with its register fails (design D13 of tighten-spec-contract)
     * @throws BranchTipUnavailableException if the register read that guards the factory-file
     *     restore did not run to its own exit — deliberately not folded into {@link
     *     GitSalvageFailedException}: a read that established nothing is a different verdict from
     *     a step that failed, and answering {@code false} instead would let the dirty working
     *     copy's factory files ride into the salvage commit (NFR-R2 of fix-envelope-medium)
     */
    @Override
    public void salvage(String taskId) {
        new EnvelopeRestore(runner, worktreeRoot, taskId).restore().ifPresent(failure -> {
            throw new GitSalvageFailedException(taskId, "restore of the factory-owned paths", failure);
        });
        if (!hasLeftovers()) {
            return;
        }
        GitCommandResult add = runner.run(worktreeRoot, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitSalvageFailedException(taskId, "git add -A", add.stderr());
        }
        String message = ClaimEpochTrailer.stamp(
                ServiceCommitMessages.salvage(), epochs.epochFor(taskId).orElse(null));
        if (new WorktreeClosingCommit(runner).commit(worktreeRoot, taskId, message)
                instanceof WorktreeClosingCommit.Outcome.Failed failed) {
            throw new GitSalvageFailedException(taskId, failed.step(), failed.evidence());
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
        discardStep("reset --hard HEAD", runner.run(worktreeRoot, "reset", "--hard", "HEAD"));
        discardStep("clean -fd", runner.run(worktreeRoot, "clean", "-fd"));
    }

    /**
     * One discard step's outcome. Best effort — a discard that fails never fails the run — but a
     * step that did not run leaves the very leftovers the discard exists to remove, so the next
     * round starts on a working copy nobody expects (FR5 of harden-logging-observability).
     */
    private void discardStep(String step, GitCommandResult result) {
        if (result.exitCode() != 0) {
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.warn(
                    OperatorEvent.WORKTREE_DISCARD_STEP_FAILED.head()
                            + "discard step 'git {}' exited {}; uncommitted leftovers stay in the worktree: {}",
                    step,
                    result.exitCode(),
                    result.stderr().forLog());
        }
    }
}
