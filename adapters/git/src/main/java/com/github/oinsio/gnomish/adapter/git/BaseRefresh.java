package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Refreshes the resolved base ref from {@code origin} before a task branch is created, fail-closed:
 * no branch is ever created from a base whose freshness could not be established (FR6).
 *
 * <p>The order is classify, then fetch. A bare commit is recognized without asking anyone; every
 * other name is put to {@code origin} once ({@link RemoteBaseRef}), and its answer decides the
 * refspec — branch into its remote-tracking ref, tag into {@code refs/tags/} without force. Both
 * namespaces at once parks the task rather than picking one (design D11a), and every kind reads its
 * commit back from the destination, never from {@code FETCH_HEAD}.
 *
 * <p>What this may change in the operator's clone is exactly the one {@code refs/remotes/origin/<n>}
 * or the one previously-absent {@code refs/tags/<n>} it names, plus the object database. The working
 * tree, the index, {@code HEAD}, every {@code refs/heads/*} and every pre-existing tag are
 * untouched — {@code fetch} touches none of them, which is why D7 of {@code add-git-workflow} could
 * keep forbidding {@code pull} while this change permits fetching.
 *
 * <p>Only the unanswered-remote arm is re-asked under {@link GitInfrastructureRetry}: every refusal
 * here is a deterministic fact about the repository, and spending an infrastructure budget on it
 * would only delay the operator's report.
 *
 * <p>Implements FR6, FR8, FR9, NFR-P1, NFR-R1 of add-base-ref-resolution.
 */
public final class BaseRefresh {

    private final GitProcessRunner runner;
    private final RemoteBaseRef remoteRef;
    private final TagBaseFetch tags;
    private final CommitBaseFetch commits;
    private final GitInfrastructureRetry retry;

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget an unanswered origin is re-asked under; never null
     */
    public BaseRefresh(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.remoteRef = new RemoteBaseRef(runner);
        this.tags = new TagBaseFetch(runner);
        this.commits = new CommitBaseFetch(runner);
        this.retry = retry;
    }

    /**
     * Refreshes {@code ref} in the clone at {@code cloneDir}.
     *
     * @param cloneDir the factory clone, already hardened; never null
     * @param ref the resolved base ref — a branch name, a tag name, or a commit SHA
     * @return the commit the task branch may start from, or which failure class stopped it
     */
    public BaseRefreshOutcome refresh(Path cloneDir, String ref) {
        return refresh(cloneDir, ref, null);
    }

    /**
     * Refreshes {@code ref}, reading only the namespace {@code pinnedKind} names when one is given.
     *
     * <p>A pinned kind is the remote's own answer from the moment the base was first resolved (D7
     * of add-base-ref-resolution, revised 2026-09-10), so a resume trusts it instead of asking
     * again: without it, a tag pushed later under a pinned branch's name would park every resume of
     * that task under the collision arm below. With no kind — a manual pin, or one written before
     * the kind existed — the name is classified exactly as a fresh claim classifies it.
     *
     * @param cloneDir the factory clone, already hardened; never null
     * @param ref the base ref — a branch name, a tag name, or a commit SHA
     * @param pinnedKind the namespace to read, or {@code null} to classify {@code ref}
     * @return the commit the task branch may start from, or which failure class stopped it
     */
    public BaseRefreshOutcome refresh(Path cloneDir, String ref, @Nullable BaseRefKind pinnedKind) {
        return retry.until(
                () -> attempt(cloneDir, ref, pinnedKind),
                outcome -> !(outcome instanceof BaseRefreshOutcome.Unavailable));
    }

    private BaseRefreshOutcome attempt(Path cloneDir, String ref, @Nullable BaseRefKind pinnedKind) {
        if (pinnedKind == BaseRefKind.COMMIT) {
            // A pinned commit is an object name, not a ref: it either is in the clone or is fetched
            // by SHA. Falling through to the ref namespaces would re-open the very ambiguity the
            // pin closed.
            return commits.fetch(cloneDir, ref)
                    .orElseGet(() -> new BaseRefreshOutcome.Refused(UntrustedText.subprocess(unheldCommitReport(ref))));
        }
        if (pinnedKind == null && CommitBaseFetch.looksLikeCommit(ref)) {
            Optional<BaseRefreshOutcome> asCommit = commits.fetch(cloneDir, ref);
            if (asCommit.isPresent()) {
                return asCommit.get();
            }
            // A hex-looking name the clone holds no object for and that is too short to fetch by:
            // it may still be an oddly named branch or tag, so it goes to origin like any other.
        }
        boolean readBranches = pinnedKind != BaseRefKind.TAG;
        boolean readTags = pinnedKind != BaseRefKind.BRANCH;
        return switch (remoteRef.read(cloneDir, ref, readBranches, readTags)) {
            case RemoteBaseRef.Held.Branch _ -> fetchBranch(cloneDir, ref);
            case RemoteBaseRef.Held.Tag(String commit) -> tags.fetch(cloneDir, ref, commit);
            case RemoteBaseRef.Held.Both(String branchCommit, String tagCommit) ->
                new BaseRefreshOutcome.Refused(UntrustedText.subprocess(collisionReport(ref, branchCommit, tagCommit)));
            case RemoteBaseRef.Held.Absent _ ->
                new BaseRefreshOutcome.Refused(UntrustedText.subprocess(absentReport(ref)));
            case RemoteBaseRef.Held.NoRemote _ ->
                new BaseRefreshOutcome.Refused(UntrustedText.subprocess(noRemoteReport(ref)));
            case RemoteBaseRef.Held.Unanswered(UntrustedText reason) -> new BaseRefreshOutcome.Unavailable(reason);
        };
    }

    private BaseRefreshOutcome fetchBranch(Path cloneDir, String name) {
        String tracking = "refs/remotes/origin/" + name;
        GitCommandResult fetch = NarrowFetch.of(runner, cloneDir, "+refs/heads/" + name + ":" + tracking);
        return RefreshedTip.of(runner, cloneDir, fetch, tracking, name, BaseRefKind.BRANCH);
    }

    private static String collisionReport(String ref, String branchCommit, String tagCommit) {
        return "The base '" + ref + "' names both a branch and a tag on origin, so it names no single commit: "
                + "refs/heads/" + ref + " is " + branchCommit + ", refs/tags/" + ref + " is " + tagCommit
                + ". Git would silently prefer the tag; the factory refuses instead, because a tag pushed over "
                + "a branch name would otherwise redirect this task's base. Rename one of them, or name a base "
                + "that exists in one namespace only.";
    }

    private static String unheldCommitReport(String ref) {
        return "The base commit " + ref + " this task is pinned to is not a commit this clone holds, and it is "
                + "too short a name to fetch by. Correct the base this task's pin names.";
    }

    private static String absentReport(String ref) {
        return "Origin holds no base named '" + ref + "': neither refs/heads/" + ref + " nor refs/tags/" + ref
                + " exists there. Push the base, or correct the base this task names.";
    }

    private static String noRemoteReport(String ref) {
        return "The base '" + ref + "' cannot be refreshed: this clone has no 'origin' remote to fetch from. "
                + "An autonomous run needs one — only a manual 'gnomish run' may branch from a clone's local "
                + "state.";
    }
}
