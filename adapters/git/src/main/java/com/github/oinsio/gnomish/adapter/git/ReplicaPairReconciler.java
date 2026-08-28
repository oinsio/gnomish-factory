package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one reconciler of the clone-versus-origin replica pair (design D8, FR8, NFR-R3), replacing
 * the host and container twins that each carried their own copy of the relation and their own
 * policy. Both modes now run this: equal or ahead keep the local tip (a later push catches origin
 * up); behind fast-forwards to origin; true divergence discards the local line and continues from
 * origin, automatically and with no operator flag.
 *
 * <p>Automatic discard is safe because the claim protocol made arbitration decidable: origin
 * advances only through legitimate lease holders, so a local commit that never reached origin is
 * not durable for the fleet and is already "nonexistent" by NFR-R3. What it replaces — stopping
 * the run and demanding manual git surgery — left a claimed task frozen for a human on a
 * conflict the protocol had already settled.
 *
 * <p>Both moves are the same write: an explicit compare-and-swap of the local ref against the tip
 * the decision was made on ({@code git update-ref <ref> <new> <expected>}), so a tip that moved in
 * between fails the swap and the branch is classified again rather than overwritten blindly.
 * Origin history is never rewritten — no force push exists on any automatic path; the local ref is
 * the only thing that moves (NFR-R3).
 *
 * <p>The two modes differ in exactly one step, which is why they are one class with one seam: host
 * mode has a working tree checked out on the branch and must be resynced to the moved ref, while
 * container mode reconciles refs alone (there is no host-side working tree for a boxed task).
 *
 * <p>Implements FR8, NFR-R3 of harden-task-branch-contract; supersedes FR9 of add-git-workflow's
 * stop-and-escalate rule.
 */
final class ReplicaPairReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReplicaPairReconciler.class);

    /**
     * How many times the classify-then-swap pass may be re-run after a losing compare-and-swap.
     * A tip moving under a held lease is already anomalous; a tip moving three times running is
     * not a race to wait out, it is a second writer, and continuing on a guess is the failure this
     * change exists to remove.
     */
    private static final int MAX_PASSES = 3;

    private final GitProcessRunner runner;
    private final Path repo;
    private final boolean syncWorkingTree;

    private ReplicaPairReconciler(GitProcessRunner runner, Path repo, boolean syncWorkingTree) {
        this.runner = runner;
        this.repo = repo;
        this.syncWorkingTree = syncWorkingTree;
    }

    /**
     * Host mode: commands run with the task worktree as {@code cwd}, so the ref reads resolve
     * against the clone's shared ref store while the working tree that gets resynced is this
     * task's own — never the owning clone's checkout (FR7 of add-git-workflow).
     */
    static ReplicaPairReconciler forWorktree(GitProcessRunner runner, Path worktreeRoot) {
        return new ReplicaPairReconciler(runner, worktreeRoot, true);
    }

    /** Container mode: refs only, in the factory clone — the boxed task has no host working tree. */
    static ReplicaPairReconciler forClone(GitProcessRunner runner, Path cloneDir) {
        return new ReplicaPairReconciler(runner, cloneDir, false);
    }

    /**
     * Fetches {@code origin/<branch>} narrowly, classifies the pair, and applies the policy.
     *
     * @param taskId the task the branch belongs to, for the repair log; never blank
     * @param branch the task branch name, e.g. {@code gnomish/PROJ-42}; never blank
     * @return the relation that was found and acted on
     */
    DivergenceOutcome reconcile(String taskId, String branch) {
        String localRef = "refs/heads/" + branch;
        String trackingRef = "refs/remotes/origin/" + branch;
        // The fetch's own outcome is deliberately not read: everything below is decided from refs
        // the clone actually holds, so a fetch killed on its deadline degrades to "no fresher
        // tracking ref", never to a wrong verdict (FR7 of bound-subprocess-commands).
        runner.run(repo, "fetch", "origin", branch + ":" + trackingRef);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String localTip = tipOf(localRef);
            String remoteTip = tipOf(trackingRef);
            // Half a pair is nothing to reconcile — and taking the null check here rather than
            // reading it back out of the verdict is what lets the swap below see two real tips.
            if (localTip == null || remoteTip == null) {
                return DivergenceOutcome.NO_REMOTE_TRACKING_REF;
            }
            DivergenceOutcome relation = ReplicaRelation.of(localTip, remoteTip, this::isAncestor);
            if (relation == DivergenceOutcome.EQUAL || relation == DivergenceOutcome.AHEAD) {
                return relation;
            }
            if (adopt(taskId, branch, localRef, localTip, remoteTip, relation)) {
                return relation;
            }
            log.warn(
                    "replica reset lost its compare-and-swap, classifying again: taskId={}, branch={}, pass={}",
                    taskId,
                    branch,
                    pass + 1);
        }
        throw new IllegalStateException("the local ref for " + branch + " of task " + taskId + " kept moving under "
                + MAX_PASSES + " reconciliation passes; a second writer holds this branch");
    }

    /**
     * Moves the local ref to {@code remoteTip} under a compare-and-swap against {@code localTip},
     * and resyncs the working tree behind it in host mode. Fast-forward and discard are the same
     * write; only the log line and what is being given up differ.
     *
     * @return {@code true} when the swap won, {@code false} when the local tip had moved
     */
    private boolean adopt(
            String taskId,
            String branch,
            String localRef,
            String localTip,
            String remoteTip,
            DivergenceOutcome relation) {
        if (runner.run(repo, "update-ref", localRef, remoteTip, localTip).exitCode() != 0) {
            return false;
        }
        if (relation == DivergenceOutcome.DIVERGED) {
            // NFR-O1: every discard is named, with both tips, so the repair is auditable.
            log.warn(
                    "discarding the local task branch under the claim: taskId={}, branch={}, discardedTip={},"
                            + " adoptedOriginTip={}",
                    taskId,
                    branch,
                    localTip,
                    remoteTip);
        } else {
            log.info("local {} is behind origin; fast-forwarding the ref to {}", branch, remoteTip);
        }
        if (syncWorkingTree) {
            runner.run(repo, "reset", "--hard", remoteTip);
            runner.run(repo, "clean", "-fd");
        }
        return true;
    }

    private boolean isAncestor(String ancestor, String descendant) {
        return runner.run(repo, "merge-base", "--is-ancestor", ancestor, descendant)
                        .exitCode()
                == 0;
    }

    private @Nullable String tipOf(String ref) {
        GitCommandResult result = runner.run(repo, "rev-parse", "--verify", "--quiet", ref);
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }
}
