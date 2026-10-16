package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.RemoteBranchTip.Carriage;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the task branch {@code gnomish/<taskId>} in a clone: a local branch, else the touchpoint
 * fetch of exactly that one ref (and the task's register) from {@code origin}, with a
 * remote-tracking ref already present as the fallback when origin cannot deliver (design D13 of
 * tighten-spec-contract, amended 2026-10-13: {@code git clone} brings a tracking ref without its
 * register, so a present tracking ref no longer ends the search). Shared verbatim by two very different callers: resume
 * (task 4.6), which goes on to materialize a worktree from whatever ref this returns, and
 * inspection (`status`/`usage`, task 5.2), which only ever reads via {@code git show <ref>:<path>}
 * and must never create a local branch or touch the working copy itself — this locator never
 * checks out anything, so both callers get the same read-only guarantee for free.
 *
 * <p>The narrow fetch uses {@code git fetch origin <branch>:refs/remotes/origin/<branch>} — an
 * explicit source:destination refspec naming exactly one branch, never {@code --all} or a
 * wildcard — which both retrieves the one ref needed and leaves a proper {@code
 * refs/remotes/origin/...} tracking ref behind, verified empirically to be readable by both {@code
 * git show} and usable as a {@code git worktree add} start point. Its argv is the transfer
 * owner's ({@code GitTransfer.fetch} in {@code :gittransfer}, ADR 0008), the one construction site
 * of every factory transfer, so "exactly one ref" is enforced by the flags as well as by the
 * refspec: without them git auto-follows tags into the operator's own {@code refs/tags/} and
 * truncates {@code FETCH_HEAD}, two writes this clone was promised it would never see. This
 * satisfies FR8's "never fetching anything else" — the one other ref the same transfer names is the
 * task's own factory register ({@link TouchpointFetch}, design D13 of tighten-spec-contract), which
 * lands in the clone's register beside the tracking ref.
 *
 * <p>A fetch that does not produce the ref is <em>not</em> absence (FR6 of
 * harden-task-branch-contract): it is a question this clone cannot answer, and only {@code origin}
 * can. So the failure path asks it — one {@code ls-remote} through {@link RemoteBranchTip}, whose
 * three-way answer is the classification: origin answered and holds no such ref → {@link
 * BranchLocation.NotFound}; origin holds it, or never answered at all → {@link
 * BranchLocation.Unavailable}, retried under {@link GitInfrastructureRetry} and, if it never
 * settles, left for the caller to stop on (a take releases its claim on it, outside the abort
 * accounting). Treating every failed fetch as absence is what forked a duplicate branch for a task
 * that already had one, and it is the status quo this replaces. With
 * no {@code origin} configured there is no one to ask and the clone's own refs are the whole truth,
 * so the local-only run keeps answering {@link BranchLocation.NotFound} exactly as before — but
 * only when the fetch itself ran to its own exit, since "no origin is configured" is read from a
 * git invocation that a shutdown or a deadline silences the same way it silenced the fetch, and a
 * silenced read must not be spent as a second vote for absence.
 *
 * <p>A fetch git's own object validation refused takes neither arm: it is asked about first,
 * from the stderr alone, and is {@link BranchLocation.Refused} — a fact about the branch's
 * history that no retry changes (FR5 of own-git-transfer-argv).
 *
 * <p>Implements FR8, FR13 of add-git-workflow; FR6 of harden-task-branch-contract; FR5 of
 * own-git-transfer-argv; FR13 of tighten-spec-contract.
 */
public final class TaskBranchLocator {

    private static final Logger log = LoggerFactory.getLogger(TaskBranchLocator.class);

    private final GitRunner runner;
    private final OriginRemote origin;
    private final RemoteBranchTip remoteTip;
    private final GitInfrastructureRetry retry;
    private final TouchpointFetch touchpoint;

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget the unsettled lookup is re-attempted under; never null
     */
    public TaskBranchLocator(GitRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.retry = retry;
        this.touchpoint = new TouchpointFetch(runner);
    }

    /**
     * Locates the task branch for {@code taskId} in the clone at {@code cloneDir}.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId; sanitized via {@link
     *     TaskIdSanitizer#branchName}
     * @return where the branch was found — local, remote-tracking (already present or
     *     just-fetched), confirmed missing everywhere, unestablished because origin could not be
     *     asked, or refused by object validation
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe branch name
     */
    public BranchLocation locate(Path cloneDir, String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        return retry.until(
                () -> attempt(cloneDir, taskId, branchName),
                located -> !(located instanceof BranchLocation.Unavailable));
    }

    private BranchLocation attempt(Path cloneDir, String taskId, String branchName) {
        String localRef = TaskBranchRefs.head(taskId);
        String trackingRef = TaskBranchRefs.tracking(taskId);

        if (refExists(cloneDir, localRef)) {
            return new BranchLocation.Local(localRef);
        }
        // A remote-tracking ref already present does not end the search (design D13 of
        // tighten-spec-contract, amended 2026-10-13): `git clone` and an operator's own fetch bring
        // the branch without its factory register, and the touchpoint fetch is the register's only
        // route into a clone with no local branch. So the fetch runs whenever no local branch
        // exists, and the present ref is only the fallback for an origin that could not deliver.
        boolean tracked = refExists(cloneDir, trackingRef);

        // The touchpoint fetch carries the factory register with the branch (design D13 of
        // tighten-spec-contract). No local branch exists here, so nothing local can be ahead of
        // origin's register: it lands in the clone's register itself.
        GitCommandResult fetch = touchpoint.fetch(
                cloneDir,
                taskId,
                new Refspec(branchName + ":" + trackingRef),
                FactoryRegisterRefs.fetchedInPlace(taskId));
        if (tracked) {
            return fetch.succeeded()
                    ? new BranchLocation.RemoteTracking(trackingRef)
                    : fallBack(branchName, trackingRef, fetch);
        }
        // The ref is the authority, not the fetch's exit code: a fetch killed on its deadline or
        // cut short by a shutdown cannot have created the tracking ref, and a fetch that reports
        // success without one has delivered nothing. Reading the ref answers all of those at once.
        if (refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }
        // No origin means no one to ask: whatever this clone holds is the whole truth, and a
        // purely local run must keep routing fresh rather than aborting forever (UX3). Only a
        // fetch that ran to its own exit may take that shortcut. The configuration read is a git
        // invocation too, and a shutdown or a deadline cuts it short exactly as it cut the fetch
        // short — "git remote get-url origin failed" is then indistinguishable from "this clone
        // has no origin", so believing it here would answer absence to a question nobody asked.
        // A fetch that never exited falls through to the classification instead, where a remote
        // that did not answer is reported as unestablished (FR6).
        if (fetch.termination() == Termination.EXITED && !origin.isConfigured(cloneDir)) {
            return new BranchLocation.NotFound();
        }
        return classifyFailedFetch(cloneDir, taskId, branchName, fetch);
    }

    /**
     * The tracking ref already present, as the answer when origin could not deliver a fresher one:
     * unreachable, no origin configured at all, or cut off. The clone's register is then whatever
     * an earlier touchpoint fetch left — none for a ref {@code git clone} brought, which the reader
     * classifies {@code Unregistered}, the honest answer offline. Object validation refusing what origin
     * served is a fact about the branch's history, not an outage, so it is never fallen back from.
     */
    private BranchLocation fallBack(String branchName, String trackingRef, GitCommandResult fetch) {
        Optional<BranchLocation> refused = refused(branchName, fetch);
        if (refused.isPresent()) {
            return refused.get();
        }
        // throwable-not-subject: git reported a status; nothing was thrown.
        log.debug(
                "touchpoint fetch of {} did not deliver ({}); falling back to the tracking ref {} already present",
                branchName,
                why(fetch).forLog(),
                trackingRef);
        return new BranchLocation.RemoteTracking(trackingRef);
    }

    /** The refusal git's own object validation made of what origin served, if that is the failure. */
    private static Optional<BranchLocation> refused(String branchName, GitCommandResult fetch) {
        return FetchRefusal.parse(fetch.stderr())
                .map(refusal -> new BranchLocation.Refused(refusal.report(
                        "The task branch " + branchName + " was found on origin",
                        FetchRefusal.Remedy.BEFORE_RETURNING_THE_TASK)));
    }

    private BranchLocation classifyFailedFetch(
            Path cloneDir, String taskId, String branchName, GitCommandResult fetch) {
        // Asked first, before origin is asked to confirm the branch (design D4 of
        // own-git-transfer-argv): validation refused what origin served, so the carriage is known
        // and the refusal is the finding — a task-level park, never the unavailable arm (FR5).
        Optional<BranchLocation> refused = refused(branchName, fetch);
        if (refused.isPresent()) {
            return refused.get();
        }
        return switch (remoteTip.confirmBranch(cloneDir, taskId)) {
            case Carriage.ABSENT -> {
                // The fetch failed and origin confirms the branch does not exist, so absence is
                // the right answer — but the failed fetch that led here is worth the trace, since
                // this is the one arm where a failure and a fact look identical (FR5).
                // throwable-not-subject: git reported a status; nothing was thrown.
                log.debug(
                        "narrow fetch of {} failed and origin confirms it is absent ({})",
                        branchName,
                        why(fetch).forLog());
                yield new BranchLocation.NotFound();
            }
            case Carriage.CARRIES ->
                new BranchLocation.Unavailable(UntrustedText.factory(
                        "origin carries " + branchName + " but the narrow fetch did not deliver it ("
                                + why(fetch).forLog() + ")"));
            case Carriage.UNKNOWN ->
                new BranchLocation.Unavailable(UntrustedText.factory("origin did not answer whether " + branchName
                        + " exists (" + why(fetch).forLog() + ")"));
        };
    }

    /**
     * Why the narrow fetch did not deliver, as the carrier git's stderr arrived in. Kept typed to
     * its own sinks — every one of the three callers takes {@link UntrustedText#forLog()}
     * explicitly: {@link #classifyFailedFetch}'s absence trace for the log plane, and that same
     * switch's two {@code Unavailable} sentences to quote
     * an already-neutralized fragment into factory prose, which is what keeps the quoting sentence
     * in the factory's own family rather than the capture's.
     */
    private static UntrustedText why(GitCommandResult fetch) {
        return fetch.failureDetail("narrow fetch");
    }

    private boolean refExists(Path cloneDir, String ref) {
        return runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref).exitCode() == 0;
    }
}
