package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code git show} half of the tip-reader seam: reads a file at a revision and searches that
 * revision's history for the cleanup commit, both as read-only subprocess calls in a given
 * repository. Shared by the two subprocess-backed sources — {@link WorktreeTipSource} reads a
 * worktree's own {@code HEAD}, {@link RefTipSource} reads any ref of a clone — so the two differ in
 * what they point at and in nothing else.
 *
 * <p>Implements FR1, FR5 of harden-task-branch-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, kept a plain final class for parity
// with its siblings in this package (see LocalBranchTip).
@SuppressWarnings("ClassCanBeRecord")
final class GitShowTip {

    private final GitProcessRunner runner;
    private final Path repo;
    private final String revision;

    GitShowTip(GitProcessRunner runner, Path repo, String revision) {
        this.runner = runner;
        this.repo = repo;
        this.revision = revision;
    }

    Optional<String> readAtTip(String path) {
        GitCommandResult result = runner.run(repo, "show", revision + ":" + path);
        return result.exitCode() == 0 ? Optional.of(result.stdout()) : Optional.empty();
    }

    /**
     * The epoch stamped on the revision's own commit message. A revision that does not resolve
     * leaves the message unread and the epoch empty — the same answer an unstamped tip gives, since
     * neither is a tip this factory can fence.
     */
    Optional<ClaimEpoch> tipEpoch() {
        GitCommandResult result = runner.run(repo, "log", "-1", "--format=%B", revision);
        return result.exitCode() == 0 ? ClaimEpochTrailer.parse(result.stdout()) : Optional.empty();
    }

    /**
     * Searches the revision's history for a commit whose message carries the cleanup subject
     * verbatim ({@code --fixed-strings}, so nothing in the message is read as a pattern). A single
     * match is enough, so the walk stops at the first one.
     *
     * <p>The verdict is "a commit id was printed", with no separate exit-code check: {@code
     * rev-list} writes its diagnostics to stderr, so an unresolvable revision leaves stdout empty
     * exactly as a clean no-match does, and the two need no distinction here.
     */
    boolean cleanupCommitInHistory() {
        GitCommandResult result = runner.run(
                repo,
                "rev-list",
                "--max-count=1",
                "--fixed-strings",
                "--grep=" + ServiceCommitMessages.cleanup(),
                revision);
        return !result.stdout().isBlank();
    }
}
