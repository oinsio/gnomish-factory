package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The walk from a registered pending commit down to the snapshot of its round (design D21 of
 * migrate-verify-to-executor-contract): first parents only, over commits whose subject is the pending
 * subject of the same stage, round and token, to the one commit carrying that round's snapshot
 * subject. Every commit on the way is judged by the writer's own rendering of the round's subjects
 * ({@link ServiceCommitMessages#pending(String, int, RoundToken)}, {@link
 * ServiceCommitMessages#snapshot}), compared whole, so a commit of another round, another token or
 * another kind ends the walk with nothing. A pending commit has exactly one parent by construction;
 * one with any other count is not the factory's and is refused too.
 *
 * <p>One walk, two answers (design D14 as amended 2026-11-13): the snapshot, and the round's
 * registered chain the walk passed — the registered pending commit first, the snapshot last — which
 * the resumed round's cell holds for the adoption of a recorded key, so nothing walks the chain again
 * (crash-consistency item 14).
 *
 * <p>A refusal is not an alarm: the pickup resumes through the ordinary path and loses only the
 * no-attempt-burned re-verification, so it is traced at DEBUG (FR5 of harden-logging-observability).
 *
 * <p>Reads one {@code git log -1} per commit in the factory clone, bare-object queries only — no
 * checkout, no hooks (FR17 of add-sandbox-core). The walk ends: history is acyclic, and every step
 * moves to a parent.
 *
 * <p>Implements FR3, FR20 of migrate-verify-to-executor-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, like its caller SnapshotTipCheck.
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
final class PendingChain {

    private static final Logger log = LoggerFactory.getLogger(PendingChain.class);

    /** One commit as {@code git log -1 --format=%P%n%s} prints it: its parents, then its subject. */
    private record Line(List<String> parents, String subject) {}

    private final GitRunner runner;
    private final Path cloneDir;

    PendingChain(GitRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
    }

    /**
     * The registered chain from {@code pending}, the registered pending commit of the round {@code
     * key} and {@code token} name, down to its snapshot.
     *
     * @param branch the task branch, for the trace only
     * @param pending the registered commit, whose subject is the round's pending subject
     * @param key the round's stage and number, from that subject
     * @param token the round's token, from that subject
     * @return the commits the walk passed, {@code pending} first and the snapshot last, or empty when
     *     a commit on the way is not the round's
     */
    Optional<List<String>> snapshotBelow(String branch, RegisteredCommit pending, AttemptKey key, RoundToken token) {
        String snapshotSubject = ServiceCommitMessages.snapshot(key.stage(), key.attempt(), token);
        String pendingSubject = ServiceCommitMessages.pending(key.stage(), key.attempt(), token);
        List<String> walked = new ArrayList<>(List.of(pending.id()));
        Optional<Line> line = read(branch, pending.id());
        while (line.isPresent() && line.get().parents().size() == 1) {
            String parent = line.get().parents().getFirst();
            walked.add(parent);
            Optional<Line> below = read(branch, parent);
            if (below.isPresent() && below.get().subject().equals(snapshotSubject)) {
                return Optional.of(List.copyOf(walked));
            }
            line = below.filter(commit -> commit.subject().equals(pendingSubject));
        }
        // throwable-not-subject: the shape is the diagnosis; nothing was thrown.
        log.debug(
                "pending commit {} of {} stands on no snapshot of its round, resuming through salvage",
                pending.id(),
                branch);
        return Optional.empty();
    }

    /** The commit's parents and subject, or empty when git cannot read it here. */
    private Optional<Line> read(String branch, String commit) {
        GitCommandResult result = runner.run(cloneDir, "log", "-1", "--format=%P%n%s", commit);
        if (result.exitCode() != 0) {
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.debug(
                    "pending walk of {} could not read {} (git exited {}): {}",
                    branch,
                    commit,
                    result.exitCode(),
                    result.stderr().forLog());
            return Optional.empty();
        }
        // @UntrustedParser warrant (design D11): what leaves is the parent ids, handed back to git as
        //     arguments, and the subject, compared whole with the writer's own rendering — never shown.
        List<String> lines = result.stdout().forParsing().strip().lines().toList();
        if (lines.size() != 2) {
            log.debug("pending walk of {} read no subject at {}: {}", branch, commit, LogText.forLog(lines.toString()));
            return Optional.empty();
        }
        return Optional.of(new Line(List.of(lines.getFirst().split(" ")), lines.get(1)));
    }
}
