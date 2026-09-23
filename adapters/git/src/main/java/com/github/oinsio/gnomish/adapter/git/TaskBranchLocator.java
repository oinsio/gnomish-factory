package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.RemoteBranchTip.Carriage;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.gittransfer.TransferSource;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the task branch {@code gnomish/<taskId>} in a clone, trying — in order, stopping at the
 * first hit — a local branch, an already-present remote-tracking ref, then a narrow fetch of
 * exactly that one ref from {@code origin}. Shared verbatim by two very different callers: resume
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
 * satisfies FR8's "never fetching anything else".
 *
 * <p>A fetch that does not produce the ref is <em>not</em> absence (FR6 of
 * harden-task-branch-contract): it is a question this clone cannot answer, and only {@code origin}
 * can. So the failure path asks it — one {@code ls-remote} through {@link RemoteBranchTip}, whose
 * three-way answer is the classification: origin answered and holds no such ref → {@link
 * BranchLocation.NotFound}; origin holds it, or never answered at all → {@link
 * BranchLocation.Unavailable}, retried under {@link GitInfrastructureRetry} and, if it never
 * settles, left for the caller to abort on. Treating every failed fetch as absence is what forked
 * a duplicate branch for a task that already had one, and it is the status quo this replaces. With
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
 * own-git-transfer-argv.
 */
public final class TaskBranchLocator {

    private static final Logger log = LoggerFactory.getLogger(TaskBranchLocator.class);

    private final GitProcessRunner runner;
    private final OriginRemote origin;
    private final RemoteBranchTip remoteTip;
    private final GitInfrastructureRetry retry;

    public TaskBranchLocator(GitProcessRunner runner) {
        this(runner, GitInfrastructureRetry.system());
    }

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget the unsettled lookup is re-attempted under; never null
     */
    public TaskBranchLocator(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.retry = retry;
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
                () -> attempt(cloneDir, branchName), located -> !(located instanceof BranchLocation.Unavailable));
    }

    private BranchLocation attempt(Path cloneDir, String branchName) {
        String localRef = "refs/heads/" + branchName;
        String trackingRef = "refs/remotes/origin/" + branchName;

        if (refExists(cloneDir, localRef)) {
            return new BranchLocation.Local(localRef);
        }
        if (refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }

        GitCommandResult fetch = runner.run(
                cloneDir, GitTransfer.fetch(TransferSource.ORIGIN, new Refspec(branchName + ":" + trackingRef)));
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
        return classifyFailedFetch(cloneDir, branchName, fetch);
    }

    private BranchLocation classifyFailedFetch(Path cloneDir, String branchName, GitCommandResult fetch) {
        // Asked first, before origin is asked to confirm the branch (design D4 of
        // own-git-transfer-argv): validation refused what origin served, so the carriage is known
        // and the refusal is the finding — a task-level park, never the unavailable arm (FR5).
        Optional<FetchRefusal> refusal = FetchRefusal.parse(fetch.stderr());
        if (refusal.isPresent()) {
            return new BranchLocation.Refused(refusal.get()
                    .report(
                            "The task branch " + branchName + " was found on origin",
                            FetchRefusal.Remedy.BEFORE_RETURNING_THE_TASK));
        }
        return switch (remoteTip.confirmBranch(cloneDir, branchName)) {
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
