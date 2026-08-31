package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
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
 * remote or an absent remote branch answers "not delivered" rather than throwing; a tip the clone
 * cannot resolve (a descendant pushed by another instance) answers {@link Carriage#UNKNOWN}, never
 * absence; the caller decides what to do about it.
 *
 * <p>The read answers three-way, not two-way ({@link Carriage}): "origin answered and does not
 * carry it" and "origin never answered" look identical in an {@link Optional}, and telling them
 * apart is exactly what stops a killed push from being reported as {@code origin is behind}
 * (design D7 of bound-subprocess-commands).
 *
 * <p>Implements FR3, FR4 of fix-lifecycle-push; FR7 of bound-subprocess-commands; FR6 of
 * harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class RemoteBranchTip {

    /**
     * What one bounded remote read established about a commit's presence on {@code origin}.
     *
     * <p>Implements FR7 of bound-subprocess-commands.
     */
    enum Carriage {
        /** {@code origin} answered, and its tip for the branch demonstrably contains the commit. */
        CARRIES,
        /** {@code origin} answered, and what it holds does not contain the commit. */
        ABSENT,
        /** {@code origin} never answered — unreachable, cut off on its deadline, or interrupted. */
        UNKNOWN
    }

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
        return tipOf(lsRemote(repo, branch));
    }

    /**
     * The three-way delivery question a caller must ask when its push did not run to its own exit:
     * only a remote that actually answered can put a commit's absence on the record.
     *
     * @param repo the clone the read runs from; never null
     * @param branch the task branch name; never blank
     * @param commit the commit whose delivery is in question; never blank
     * @return whether {@code origin} carries {@code commit}, demonstrably does not, or did not say
     */
    Carriage confirm(Path repo, String branch, String commit) {
        GitCommandResult lsRemote = lsRemote(repo, branch);
        if (lsRemote.termination() != Termination.EXITED || lsRemote.exitCode() != 0) {
            return Carriage.UNKNOWN;
        }
        // An answered read with no ref for the branch is a positive fact: origin does not have it.
        return tipOf(lsRemote)
                .map(remoteTip -> ancestryVerdict(runner.run(repo, "merge-base", "--is-ancestor", commit, remoteTip)))
                .orElse(Carriage.ABSENT);
    }

    /**
     * Maps one {@code merge-base --is-ancestor} result onto {@link Carriage}. Git's documented
     * contract for the command is exit 0 = ancestor, exit 1 = not an ancestor, any other status =
     * error — which includes a remote tip the clone cannot resolve (a descendant pushed by another
     * instance). Only the documented "no" is allowed to put absence on the record; an error, like
     * an invocation that never ran to its own exit, is {@link Carriage#UNKNOWN}.
     *
     * <p>Implements FR7 of bound-subprocess-commands.
     */
    static Carriage ancestryVerdict(GitCommandResult ancestry) {
        if (ancestry.termination() != Termination.EXITED) {
            return Carriage.UNKNOWN;
        }
        return switch (ancestry.exitCode()) {
            case 0 -> Carriage.CARRIES;
            case 1 -> Carriage.ABSENT;
            default -> Carriage.UNKNOWN;
        };
    }

    /**
     * The three-way presence question about the branch itself, as opposed to {@link #confirm}'s
     * question about one commit: only a remote that actually answered can put a branch's absence
     * on the record, and absence is what routes a take to a fresh claim (FR6 of
     * harden-task-branch-contract).
     *
     * @param repo the clone the read runs from; never null
     * @param branch the task branch name; never blank
     * @return {@link Carriage#CARRIES} when origin holds the branch, {@link Carriage#ABSENT} when
     *     it answered and holds no such ref, {@link Carriage#UNKNOWN} when it did not answer
     */
    Carriage confirmBranch(Path repo, String branch) {
        GitCommandResult lsRemote = lsRemote(repo, branch);
        if (lsRemote.termination() != Termination.EXITED || lsRemote.exitCode() != 0) {
            return Carriage.UNKNOWN;
        }
        return lsRemote.stdout().isBlank() ? Carriage.ABSENT : Carriage.CARRIES;
    }

    private GitCommandResult lsRemote(Path repo, String branch) {
        return runner.run(repo, "ls-remote", OriginRemote.NAME, "refs/heads/" + branch);
    }

    private static Optional<String> tipOf(GitCommandResult lsRemote) {
        if (lsRemote.termination() != Termination.EXITED
                || lsRemote.exitCode() != 0
                || lsRemote.stdout().isBlank()) {
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
        return confirm(repo, branch, commit) == Carriage.CARRIES;
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
