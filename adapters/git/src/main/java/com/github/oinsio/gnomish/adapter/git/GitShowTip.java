package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code git show} half of the tip-reader seam: reads a file at a revision, tests whether the
 * revision's tree carries a path, and searches that revision's history for the cleanup commit —
 * all as read-only subprocess calls in a given repository. {@link RefTipSource} is its one wrapper, and the (repository, revision) pair is the
 * only thing that varies between the media it serves: a worktree with {@code HEAD}, a clone with a
 * branch or remote-tracking ref, a bare repository with either.
 *
 * <p>This is the seam that classifies a read's invocation outcome (design D3, D14): every answer
 * below is a positive fact about the tip, so an invocation that never ran to its own exit — cut
 * off on a deadline, or interrupted by a shutdown — throws {@link BranchTipUnavailableException}
 * rather than returning the answer an absent file or an unsearched history would give. Reading a
 * non-exit as absence is how a live branch classifies as {@code Bare} and a take forks a second
 * branch for a task that already has one.
 *
 * <p>Implements FR1, FR5, FR6 of harden-task-branch-contract, FR4, FR6 and NFR-R2 of
 * fix-envelope-medium.
 */
// Not a record: a behavior-bearing reader over the git seam, kept a plain final class for parity
// with its siblings in this package (see LocalBranchTip).
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
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

    Optional<UntrustedText> readAtTip(String path) {
        GitCommandResult result = showAtTip(path);
        if (result.exitCode() != 0) {
            warnAbsent(path, result);
            return Optional.empty();
        }
        return Optional.of(result.stdout());
    }

    /**
     * The same read as {@link #readAtTip}, with git's own refusal kept instead of collapsed into
     * an empty result — for the caller that must not classify a non-zero exit as absence but
     * report why the file could not be read. A revision with no such path, an unborn {@code HEAD}
     * and a corrupt object all exit non-zero here and say which in {@code stderr}; a reader that
     * only sees the emptiness can name none of them.
     *
     * <p>The gate is the same one {@link #readAtTip} passes: an invocation cut off before its own
     * exit is unavailability, not a fact about the tip.
     *
     * @param path the repository-relative path to read at the revision
     * @return the invocation's own result, exit code and captured streams included
     */
    GitCommandResult showAtTip(String path) {
        return GitReadGate.answered(revision, "show", runner.run(repo, "show", revision + ":" + path));
    }

    /**
     * Records that a non-zero exit is about to be read as absence, with git's own diagnosis kept (FR5 of
     * harden-logging-observability). DEBUG, not WARN: an unresolvable revision or an absent file
     * is the normal outcome this reader classifies — the branch may legitimately not exist — but
     * the classification is the only place git's reason for it survives, and NG1 keeps the
     * behavior itself unchanged.
     */
    private void warnAbsent(String subject, GitCommandResult result) {
        // throwable-not-subject: git reported a status, not a thrown fault.
        log.debug(
                "git show of {} at {} exited {}, reading as absent: {}",
                subject,
                revision,
                result.exitCode(),
                result.stderr().forLog());
    }

    /**
     * Whether the revision's tree carries an entry at {@code path} — {@code git cat-file -e
     * <revision>:<path>}, which resolves the tree entry and exits 0 when it exists, without
     * writing its content. The one owner of that fact for the host medium (design D3): the cleanup
     * guard and the salvage restore guard both ask here rather than testing the working copy or
     * spawning their own {@code cat-file}.
     *
     * <p>The answer is a positive fact about the tip, so it passes {@link GitReadGate#answered}
     * like every other read here: an invocation cut off before its own exit throws {@link
     * BranchTipUnavailableException} instead of answering {@code false} (NFR-R2) — answering
     * {@code false} would let a killed read report a live envelope as already cleaned up.
     *
     * @param path the repository-relative path to test, e.g. {@code .gnomish-task}
     * @return {@code true} when the revision's tree carries an entry at that path
     *     <p>Implements FR4, NFR-R2 of fix-envelope-medium.
     */
    boolean carries(String path) {
        GitCommandResult result =
                GitReadGate.answered(revision, "cat-file", runner.run(repo, "cat-file", "-e", revision + ":" + path));
        return result.exitCode() == 0;
    }

    /**
     * Searches the task's own history for a commit whose message carries the cleanup subject
     * verbatim ({@code --fixed-strings}, so nothing in the message is read as a pattern). A single
     * match is enough, so the walk stops at the first one.
     *
     * <p>"Own history" is the first-parent line from the revision back to this task's STARTED
     * commit, exclusive. The branch forks from a base that may already hold an earlier task's
     * cleanup commit — its branch merged with history — and a walk over everything reachable would
     * read that foreign delivery as this task's, so an escalated or interrupted task would classify
     * as {@code Delivered}. The STARTED commit is located the same way — the nearest one on the
     * first-parent line, since an earlier task's STARTED commit can sit further back in the base —
     * and a revision with none has no delivery of its own. First-parent also keeps a base merged
     * into the task branch after it started from contributing its cleanup commits.
     *
     * <p>The verdict is "a commit id was printed", with no separate exit-code check: {@code
     * rev-list} writes its diagnostics to stderr, so an unresolvable revision leaves stdout empty
     * exactly as a clean no-match does, and the two need no distinction here.
     *
     * <p>The located id — not merely its existence — is what a reader of a delivered branch needs
     * (design D5): the {@code Completed} envelope stands at that commit's parent, and assuming
     * {@code tip^} instead is wrong for every branch that gained commits after cleanup.
     *
     * @return the cleanup commit's id, or empty when the task's own history holds none
     *     <p>Implements FR1 of harden-task-branch-contract; FR6 of fix-envelope-medium;
     *     FR14 of fix-operator-blockers, with its NFR-R4.
     */
    Optional<String> cleanupCommit() {
        return nearestOwnCommit(ServiceCommitMessages.taskEvent(TaskLifecycleEvent.STARTED), revision)
                .flatMap(started -> nearestOwnCommit(ServiceCommitMessages.cleanup(), revision, "^" + started));
    }

    /**
     * The nearest commit on the revision's first-parent line whose message carries {@code message}
     * verbatim, or empty when none does.
     */
    private Optional<String> nearestOwnCommit(String message, String... revisions) {
        List<String> args = new ArrayList<>(
                List.of("rev-list", "--first-parent", "--max-count=1", "--fixed-strings", "--grep=" + message));
        args.addAll(List.of(revisions));
        GitCommandResult result =
                GitReadGate.answered(revision, "rev-list", runner.run(repo, args.toArray(String[]::new)));
        // @UntrustedParser warrant (design D11 of type-untrusted-text): what leaves here is git's
        //     own object id for a commit in this repository — used only as a revision argument and
        //     compared for equality, never rendered to a reader, so no untrusted text escapes as
        //     text. The file content readAtTip answers is the document itself and stays a carrier.
        return result.stdout().isBlank()
                ? Optional.empty()
                : Optional.of(result.stdout().forParsing().trim());
    }

    /** The classifier's fact: whether {@link #cleanupCommit()} found one. */
    boolean cleanupCommitInHistory() {
        return cleanupCommit().isPresent();
    }
}
