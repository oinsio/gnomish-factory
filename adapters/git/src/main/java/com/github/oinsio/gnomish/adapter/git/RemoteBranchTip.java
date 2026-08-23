package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The single construction site of the factory's remote-refs read (design D2 of fix-lifecycle-push):
 * {@code git ls-remote origin refs/heads/<branch>}, plus the local-ancestry answer derived from
 * whatever it returns. Extracted from {@code RemoteAttemptDelivery.deliveredPerRemoteTip}, which
 * had the only copy, and now shared by every point that must know what {@code origin} holds — the
 * attempt-commit delivery check (FR21 of add-sandbox-core), the touchpoint reconciliation (FR3),
 * and the park fence (FR4).
 *
 * <p>Cheap by design: one remote round-trip, and the ancestry question is then answered from the
 * local object database — the factory clone already has every object it authored. An unreachable
 * remote, an absent remote branch, or a tip the clone does not know all answer "not delivered"
 * rather than throwing; the caller decides what to do about it.
 *
 * <p>Implements FR3, FR4 of fix-lifecycle-push.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class RemoteBranchTip {

    private final GitProcessRunner runner;

    RemoteBranchTip(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Reads the commit {@code origin} currently holds for {@code branch}.
     *
     * @param repo the clone the read runs from; never null
     * @param branch the task branch name; never blank
     * @return the remote tip's sha, or empty when the remote is unreachable or does not carry the
     *     branch at all
     */
    Optional<String> read(Path repo, String branch) {
        GitCommandResult lsRemote = runner.run(repo, "ls-remote", OriginRemote.NAME, "refs/heads/" + branch);
        if (lsRemote.exitCode() != 0 || lsRemote.stdout().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(lsRemote.stdout().strip().split("\\s+", 2)[0]);
    }

    /**
     * The cheap delivery confirmation: {@code origin}'s tip for {@code branch}, when it is an
     * object the clone already has, proves {@code commit} delivered iff {@code commit} is its
     * ancestor.
     *
     * @param repo the clone the read runs from; never null
     * @param branch the task branch name; never blank
     * @param commit the commit whose delivery is in question; never blank
     * @return {@code true} only when the remote tip demonstrably contains {@code commit}
     */
    boolean carries(Path repo, String branch, String commit) {
        return read(repo, branch)
                .map(remoteTip -> isAncestor(repo, commit, remoteTip))
                .orElse(false);
    }

    /**
     * Local-only ancestry: whether {@code candidate} is an ancestor of (or equal to)
     * {@code descendant} in {@code repo}'s object database. Answers {@code false} for any commit
     * the clone does not know.
     */
    boolean isAncestor(Path repo, String candidate, String descendant) {
        return runner.run(repo, "merge-base", "--is-ancestor", candidate, descendant)
                        .exitCode()
                == 0;
    }
}
