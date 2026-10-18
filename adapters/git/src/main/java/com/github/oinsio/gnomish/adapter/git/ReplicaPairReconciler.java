package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergedBranchException;
import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.Optional;
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
 * not durable for the fleet and is already "nonexistent" by NFR-R3. What it replaces — stopping the
 * run and demanding manual git surgery — left a claimed task frozen on a conflict the protocol had
 * already settled.
 *
 * <p>That justification is about the protocol, not about this class, so the discard is gated on the
 * protocol being in force here: only a task under a tenure ({@link ClaimEpochSource#epochFor}) has
 * its diverged local line discarded. The claimless paths — {@code gnomish run --resume} in either
 * mode, which carry no tracker and so no claim — fail closed with {@link DivergedBranchException},
 * since there the local line may be the operator's only copy and no lease ever arbitrated it
 * against origin. The gate is the local tenure record rather than a fresh tracker read before the
 * swap on purpose: a lease re-check immediately before a write buys no safety (the writer can pause
 * between the two), and this write moves only a local ref no other instance reads — the writes that
 * do reach shared media are fenced where they land. EQUAL, AHEAD and BEHIND stay ungated: none
 * discards anything a claim could arbitrate.
 *
 * <p>Fast-forward and discard are the same write: an explicit compare-and-swap of the local ref
 * against the tip the decision was made on — one ref transaction through {@link
 * FactoryRegister#adopt}, which moves the register to origin's in the same step — so a tip
 * that moved in between fails the swap and the branch is classified again rather than overwritten
 * blindly. Origin history is never rewritten — no force push exists on any automatic path; the
 * local ref is the only thing that moves (NFR-R3).
 *
 * <p>The two modes differ in exactly one step, which is why they are one class with one seam: host
 * mode has a working tree checked out on the branch and must be resynced to the moved ref, while
 * container mode reconciles refs alone. That resync is the second durable step of the same repair,
 * so its own failure is a named refusal rather than a swallowed exit code: a moved ref whose
 * working tree still holds the discarded line is a state no later reader can classify — salvage
 * would read those files as the interrupted round's work and commit the discarded line straight
 * back on top of the adopted tip, inverting the discard this class exists to perform.
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

    private final GitRunner runner;
    private final Path repo;
    private final boolean syncWorkingTree;
    private final ClaimEpochSource epochs;
    private final TouchpointFetch touchpoint;
    private final FactoryRegister register;

    private ReplicaPairReconciler(GitRunner runner, Path repo, boolean syncWorkingTree, ClaimEpochSource epochs) {
        this.runner = runner;
        this.repo = repo;
        this.syncWorkingTree = syncWorkingTree;
        this.epochs = epochs;
        this.touchpoint = new TouchpointFetch(runner);
        this.register = new FactoryRegister(runner);
    }

    /**
     * Host mode: commands run with the task worktree as {@code cwd}, so the ref reads resolve
     * against the clone's shared ref store while the working tree that gets resynced is this
     * task's own — never the owning clone's checkout (FR7 of add-git-workflow).
     *
     * @param epochs the tenure this instance holds on the task, which gates the discard; {@link
     *     ClaimEpochSource#NONE} on the claimless manual-resume path
     */
    static ReplicaPairReconciler forWorktree(GitRunner runner, Path worktreeRoot, ClaimEpochSource epochs) {
        return new ReplicaPairReconciler(runner, worktreeRoot, true, epochs);
    }

    /** Container mode: refs only, in the factory clone — the boxed task has no host working tree. */
    static ReplicaPairReconciler forClone(GitRunner runner, Path cloneDir, ClaimEpochSource epochs) {
        return new ReplicaPairReconciler(runner, cloneDir, false, epochs);
    }

    /**
     * Fetches {@code origin/<branch>} narrowly, classifies the pair, and applies the policy.
     *
     * @param taskId the task the branch belongs to, for the repair log; never blank
     * @param branch the task branch name, e.g. {@code gnomish/PROJ-42}; never blank
     * @return the relation that was found and acted on
     * @throws DivergedBranchException when the pair has truly diverged and this instance holds no
     *     tenure on {@code taskId}: the discard's justification is the claim protocol, so a
     *     claimless path hands the decision back instead of destroying the local line
     */
    DivergenceOutcome reconcile(String taskId, String branch) {
        String localRef = TaskBranchRefs.head(taskId);
        String trackingRef = TaskBranchRefs.tracking(taskId);
        // The fetch's own outcome is deliberately not read: everything below is decided from refs
        // the clone actually holds, so a fetch killed on its deadline degrades to "no fresher
        // tracking ref", never to a wrong verdict (FR7 of bound-subprocess-commands). Its argv is
        // the transfer owner's, the one construction site every factory transfer shares, so this
        // one cannot auto-follow a tag or truncate FETCH_HEAD either (ADR 0008).
        // The register rides along into its tracking name (D13 of tighten-spec-contract).
        touchpoint.fetch(
                repo, taskId, new Refspec(branch + ":" + trackingRef), FactoryRegisterRefs.fetchedTracking(taskId));

        String lastLostSwap = null;
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
                lastLostSwap = followOriginRegister(taskId, localRef, localTip);
                if (lastLostSwap == null) {
                    return relation;
                }
                logLostSwap(taskId, branch, pass, lastLostSwap);
                continue;
            }
            if (relation == DivergenceOutcome.DIVERGED && tenureOn(taskId).isEmpty()) {
                throw new DivergedBranchException(taskId, branch, localTip, remoteTip);
            }
            lastLostSwap = adopt(taskId, branch, localRef, localTip, remoteTip, relation);
            if (lastLostSwap == null) {
                return relation;
            }
            logLostSwap(taskId, branch, pass, lastLostSwap);
        }
        throw new IllegalStateException("the local ref for " + branch + " of task " + taskId + " kept moving under "
                + MAX_PASSES + " reconciliation passes; a second writer holds this branch"
                + " (the last update-ref reported: " + lastLostSwap + ")");
    }

    private static void logLostSwap(String taskId, String branch, int pass, String lostSwap) {
        log.warn(
                OperatorEvent.REPLICA_RESET_LOST_CAS.head()
                        + "replica reset lost its compare-and-swap, classifying again: taskId={}, branch={}, pass={},"
                        + " update-ref said: {}",
                taskId,
                branch,
                pass + 1,
                lostSwap);
    }

    /**
     * Where the local branch already holds origin's line (equal or ahead), brings the clone's
     * register forward to origin's as the touchpoint fetch landed it (design D13 of
     * tighten-spec-contract, amended 2026-10-13, cross-instance resume finding): a clone that got
     * the branch from {@code git clone} or a plain fetch holds no register, and nothing else moves
     * one into it. The register only ever moves forward — absent, or an ancestor of origin's — and
     * only to a commit on the local line: a closing commit whose push was lost and whose branch a
     * later branch-only push carried leaves the clone's register ahead of origin's, and rolling it
     * back would hand the reader an older record than this clone made.
     *
     * @return {@code null} when nothing was to move or the move landed; the losing invocation's
     *     stderr when the branch or the register moved under the compare-and-swap
     * @throws IllegalStateException when the transaction was cut off before git answered
     */
    private @Nullable String followOriginRegister(String taskId, String localRef, String localTip) {
        String originRegister = tipOf(FactoryRegisterRefs.of(taskId).trackingName());
        if (originRegister == null) {
            return null;
        }
        String localRegister = tipOf(FactoryRegisterRefs.of(taskId).name());
        if (originRegister.equals(localRegister)
                || (localRegister != null && !isAncestor(localRegister, originRegister))
                || !isAncestor(originRegister, localTip)) {
            return null;
        }
        GitCommandResult swap = register.follow(repo, taskId, localRef, localTip, localRegister, originRegister);
        if (swap.termination() != Termination.EXITED) {
            throw new IllegalStateException("could not bring the register of task " + taskId + " up to origin's:"
                    + " git update-ref did not run to its own exit ("
                    + swap.termination().name()
                    + "), so the swap's outcome is unknown; resume the task to re-run the reconciliation");
        }
        if (swap.exitCode() != 0) {
            return swap.stderr().isBlank() ? "(no stderr)" : swap.stderr().forLog();
        }
        log.info("register of task {} brought up to origin's: {} -> {}", taskId, localRegister, originRegister);
        return null;
    }

    /**
     * Moves the local ref to {@code remoteTip} under a compare-and-swap against {@code localTip},
     * and resyncs the working tree behind it in host mode. Fast-forward and discard are the same
     * write; only the log line and what is being given up differ.
     *
     * @return {@code null} when the swap won; the losing invocation's stderr when the local tip had
     *     moved, so the caller's retry log and its second-writer diagnosis carry git's own account
     *     of why the swap was refused rather than asserting one
     * @throws IllegalStateException in host mode when the ref moved but the working tree could not
     *     be resynced behind it, or when the swap was cut off before git answered — an interrupted
     *     {@code update-ref} left the swap's outcome unknown, which is neither "won" nor "the tip
     *     moved", and counting it as a losing pass turns a shutdown into a second-writer diagnosis
     */
    private @Nullable String adopt(
            String taskId,
            String branch,
            String localRef,
            String localTip,
            String remoteTip,
            DivergenceOutcome relation) {
        // The local line becomes origin's, and the register follows origin's in the same ref
        // transaction (D13 of tighten-spec-contract), so no kill separates the two moves. A clone
        // register off origin's line names a commit being given up: with no register at origin to
        // replace it, the same transaction deletes it rather than leave every later read Diverged.
        String localRegister = tipOf(FactoryRegisterRefs.of(taskId).name());
        String offLineRegister = localRegister != null && !isAncestor(localRegister, remoteTip) ? localRegister : null;
        GitCommandResult swap = register.adopt(repo, taskId, localRef, remoteTip, localTip, offLineRegister);
        if (swap.termination() != Termination.EXITED) {
            throw new IllegalStateException("could not reconcile " + branch + " of task " + taskId + ": git update-ref "
                    + "did not run to its own exit (" + swap.termination().name()
                    + "), so the swap's outcome is unknown; resume the task to re-run the reconciliation");
        }
        if (swap.exitCode() != 0) {
            return swap.stderr().isBlank() ? "(no stderr)" : swap.stderr().forLog();
        }
        if (relation == DivergenceOutcome.DIVERGED) {
            // NFR-O1: every discard is named, with both tips and the tenure it ran under, so the
            // repair is auditable. The tenure is present by construction — reconcile() refuses a
            // claimless discard before reaching here — and is logged as the evidence of that.
            log.warn(
                    OperatorEvent.REPLICA_LOCAL_BRANCH_DISCARDED.head()
                            + "discarding the local task branch under the claim: taskId={}, branch={}, epoch={},"
                            + " discardedTip={}, adoptedOriginTip={}",
                    taskId,
                    branch,
                    tenureOn(taskId).map(ClaimEpoch::token).orElse(null),
                    localTip,
                    remoteTip);
        } else {
            log.info("local {} is behind origin; fast-forwarding the ref to {}", branch, remoteTip);
        }
        if (syncWorkingTree) {
            new WorktreeResync(runner, repo).resync(taskId, branch, localTip, remoteTip);
        }
        return null;
    }

    /** The tenure this instance holds on {@code taskId} right now; empty on a claimless path. */
    private Optional<ClaimEpoch> tenureOn(String taskId) {
        return epochs.epochFor(taskId);
    }

    // GitReadGate.answered: an interrupted `merge-base` would otherwise answer "not an ancestor"
    // for a pair it never compared — classifying a merely BEHIND or AHEAD pair as DIVERGED and,
    // under a live tenure, discarding the local line; an interrupted `rev-parse` can hand back a
    // truncated tip with the same verdict.
    private boolean isAncestor(String ancestor, String descendant) {
        return GitReadGate.answered(
                                ancestor,
                                "merge-base",
                                runner.run(repo, "merge-base", "--is-ancestor", ancestor, descendant))
                        .exitCode()
                == 0;
    }

    private @Nullable String tipOf(String ref) {
        GitCommandResult result =
                GitReadGate.answered(ref, "rev-parse", runner.run(repo, "rev-parse", "--verify", "--quiet", ref));
        // A rev-parse answer is an object id, used for ancestry comparisons and update-ref
        //     arguments: a zero exit that printed anything else is refused (VerifiedTip), never a
        //     half pair (FR17 of fix-terminal-receipt-convergence).
        return result.exitCode() == 0 ? VerifiedTip.shaped(ref, "rev-parse", result) : null;
    }
}
