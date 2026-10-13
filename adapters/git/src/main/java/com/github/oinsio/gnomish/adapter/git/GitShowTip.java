package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code git show} half of the tip-reader seam, at the task's registered commit (design D13 of
 * tighten-spec-contract): reads a file there and says whether that commit is the cleanup commit —
 * read-only subprocess calls in a given repository. It takes a {@link RegisteredCommit} and nothing
 * else, so no caller can point it at the branch tip or at {@code HEAD}: the gnome writes above the
 * register, and nothing it writes there is read here. {@link RefTipSource} is its wrapper for the
 * classification; the delivered-state, snapshot and host-store readers use it directly.
 *
 * <p>This is the seam that classifies a read's invocation outcome (design D3, D14 of
 * harden-task-branch-contract): every answer below is a positive fact about the registered commit,
 * so an invocation that never ran to its own exit — cut off on a deadline, or interrupted by a
 * shutdown — throws {@link BranchTipUnavailableException} rather than returning the answer an
 * absent file would give. Reading a non-exit as absence is how a live branch classifies as {@code
 * Bare} and a take forks a second branch for a task that already has one.
 *
 * <p>Implements FR1, FR5, FR6 of harden-task-branch-contract, FR4, FR6 and NFR-R2 of
 * fix-envelope-medium; FR13, NFR-S2 of tighten-spec-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, kept a plain final class for parity
// with its siblings in this package (see LocalBranchTip).
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
final class GitShowTip {

    private static final Logger log = LoggerFactory.getLogger(GitShowTip.class);

    private final GitRunner runner;
    private final Path repo;
    private final String revision;

    /**
     * @param runner the git subprocess seam
     * @param repo the clone, or a worktree of it
     * @param registered the registered commit every read here is made at
     */
    GitShowTip(GitRunner runner, Path repo, RegisteredCommit registered) {
        this.runner = runner;
        this.repo = repo;
        this.revision = registered.id();
    }

    /**
     * Reads one file as it stands at the registered commit.
     *
     * @param path the repository-relative path, e.g. {@code .gnomish-task/task.json}
     * @return the file's text, still a carrier, or empty when the registered commit does not carry it
     */
    Optional<UntrustedText> readAtRegister(String path) {
        GitCommandResult result =
                GitReadGate.answered(revision, "show", runner.run(repo, "show", revision + ":" + path));
        if (result.exitCode() != 0) {
            // DEBUG, not WARN: an absent file is the normal outcome this reader classifies, but the
            // classification is the only place git's reason for it survives (FR5 of
            // harden-logging-observability).
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.debug(
                    "git show of {} at {} exited {}, reading as absent: {}",
                    path,
                    revision,
                    result.exitCode(),
                    result.stderr().forLog());
            return Optional.empty();
        }
        return Optional.of(result.stdout());
    }

    /**
     * Whether the registered commit is the cleanup commit — the delivery fact ({@code Delivered} is
     * "the registered commit is the cleanup commit"). Only the registered commit's own subject is
     * read: a gnome commit carrying the cleanup subject, above the register or anywhere in history,
     * is never consulted, and no walk of the history happens at all.
     *
     * @return {@code true} when the registered commit's subject is the cleanup subject
     */
    boolean isCleanupCommit() {
        GitCommandResult result =
                GitReadGate.answered(revision, "log", runner.run(repo, "log", "-1", "--format=%s", revision));
        // @UntrustedParser warrant (design D11 of type-untrusted-text): a commit subject the factory
        //     itself wrote (the register names only factory closing commits), compared for equality
        //     with the fixed cleanup subject; only the boolean leaves.
        return result.exitCode() == 0
                && ServiceCommitMessages.cleanup()
                        .equals(result.stdout().forParsing().strip());
    }
}
