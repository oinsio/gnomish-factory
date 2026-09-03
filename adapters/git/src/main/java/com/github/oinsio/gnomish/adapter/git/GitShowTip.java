package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code git show} half of the tip-reader seam: reads a file at a revision and searches that
 * revision's history for the cleanup commit, both as read-only subprocess calls in a given
 * repository. {@link RefTipSource} is its one wrapper, and the (repository, revision) pair is the
 * only thing that varies between the media it serves: a worktree with {@code HEAD}, a clone with a
 * branch or remote-tracking ref, a bare repository with either.
 *
 * <p>This is the seam that classifies a read's invocation outcome (design D3, D14): every answer
 * below is a positive fact about the tip, so an invocation that never ran to its own exit — cut
 * off on a deadline, or interrupted by a shutdown — throws {@link BranchTipUnavailableException}
 * rather than returning the answer an absent file, an unstamped commit, or an unsearched history
 * would give. Reading a non-exit as absence is how a live branch classifies as {@code Bare} and a
 * take forks a second branch for a task that already has one.
 *
 * <p>Implements FR1, FR5, FR6, FR13 of harden-task-branch-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, kept a plain final class for parity
// with its siblings in this package (see LocalBranchTip).
@SuppressWarnings("ClassCanBeRecord")
final class GitShowTip {

    private static final Logger log = LoggerFactory.getLogger(GitShowTip.class);

    private final GitProcessRunner runner;
    private final Path repo;
    private final String revision;

    GitShowTip(GitProcessRunner runner, Path repo, String revision) {
        this.runner = runner;
        this.repo = repo;
        this.revision = revision;
    }

    Optional<String> readAtTip(String path) {
        GitCommandResult result = answered("show", runner.run(repo, "show", revision + ":" + path));
        if (result.exitCode() != 0) {
            warnAbsent("show", path, result);
            return Optional.empty();
        }
        return Optional.of(result.stdout());
    }

    /**
     * The epoch stamped on the revision's own commit message. A revision that does not resolve
     * leaves the message unread and the epoch empty — the same answer an unstamped tip gives, since
     * neither is a tip this factory can fence.
     */
    Optional<ClaimEpoch> tipEpoch() {
        GitCommandResult result = answered("log", runner.run(repo, "log", "-1", "--format=%B", revision));
        if (result.exitCode() != 0) {
            warnAbsent("log", "commit message", result);
            return Optional.empty();
        }
        return ClaimEpochTrailer.parse(result.stdout());
    }

    /**
     * Records that a non-zero exit is about to be read as absence, with git's own diagnosis kept (FR5 of
     * harden-logging-observability). DEBUG, not WARN: an unresolvable revision or an absent file
     * is the normal outcome this reader classifies — the branch may legitimately not exist — but
     * the classification is the only place git's reason for it survives, and NG1 keeps the
     * behavior itself unchanged.
     */
    private void warnAbsent(String command, String subject, GitCommandResult result) {
        // throwable-not-subject: git reported a status, not a thrown fault.
        log.debug(
                "git {} of {} at {} exited {}, reading as absent: {}",
                command,
                subject,
                revision,
                result.exitCode(),
                LogText.forLog(result.stderr()));
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
        GitCommandResult result = answered(
                "rev-list",
                runner.run(
                        repo,
                        "rev-list",
                        "--max-count=1",
                        "--fixed-strings",
                        "--grep=" + ServiceCommitMessages.cleanup(),
                        revision));
        return !result.stdout().isBlank();
    }

    /**
     * The one gate every read above passes through: a result is a fact about the tip only when the
     * invocation ran to its own exit. An interrupted read's capture may also be a prefix of the
     * real output ({@code CaptureRunner} reports a clean exit under a set interrupt flag as
     * {@link Termination#INTERRUPTED}), so a truncated envelope cannot reach the parser and be
     * diagnosed as corruption either.
     */
    private GitCommandResult answered(String command, GitCommandResult result) {
        return switch (result.termination()) {
            case EXITED -> result;
            case TIMED_OUT, INTERRUPTED ->
                throw new BranchTipUnavailableException(
                        revision, command, result.termination().name());
        };
    }
}
