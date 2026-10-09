package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.RoundToken;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a task branch tip on resume (FR21, design D15): a tip carrying the
 * snapshot commit message — {@code gnomish: snapshot <stage>#<round> <token>} — means
 * the factory died between the snapshot and the state commit, i.e. <em>during
 * verification</em>. The resuming instance re-runs verification against exactly
 * that attempt commit and the attempt counter is unchanged — no attempt burned
 * for a verdict that was never recorded. Any other tip (a state commit, a
 * lifecycle commit, the gnome's own commits from an interrupted round) resumes
 * through the ordinary salvage path.
 *
 * <p>The subject's round token names the round that wrote the snapshot, and with the stage and
 * round fixes the one path that round's decision request could live at ({@link
 * HarvestedBoundaryCheck#decisionPath}, the one spelling): the request — if any — is read from the
 * snapshot's own tree, so a round killed after asking re-raises its question rather than passing
 * as completed (FR15, NFR-R4 of make-checkpoint-gate-durable, design D10). A subject without a
 * readable token is refused like any other malformed one. The token itself travels on, typed, in
 * the {@link PendingVerification} ({@link RoundToken#of} — the resumed round's one producer of its
 * identity, which reuses the recorded token and never mints), so the resuming executor restores
 * the round from the record rather than from a re-read tip (FR13 of make-checkpoint-gate-durable,
 * design D10 as amended 2026-10-07).
 *
 * <p>Reads only the commit subject and one blob as bare-object queries in the factory clone
 * — no checkout, no hooks (FR17). The snapshot message is the one service
 * message that is deliberately a parsing contract (see {@link
 * ServiceCommitMessages#snapshot}).
 *
 * <p>Implements FR21 of add-sandbox-core; FR15 of make-checkpoint-gate-durable.
 */
// Not a record: a behavior-bearing reader over the git seam, kept a plain final class for parity
// with its siblings in this package (see GitShowTip, VerifiedTip).
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
public final class SnapshotTipCheck {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTipCheck.class);

    // Single owner: reads ServiceCommitMessages#SNAPSHOT_PREFIX rather than repeating the
    // literal, so the writer and this parser cannot drift (manual-sync-pairs.md, preference 1).
    private static final String SUBJECT_PREFIX = ServiceCommitMessages.SNAPSHOT_PREFIX;

    /**
     * What follows the prefix: {@code <stage>#<round> <token>}. The stage is everything before the
     * last {@code #}; the token has {@link RoundToken}'s own shape, so a subject the writer could
     * not have produced — the token-less form included — is refused here rather than half-read.
     */
    private static final Pattern STAGE_ROUND_TOKEN = Pattern.compile("(.+)#([0-9]+) ([0-9a-f]+)");

    private final GitProcessRunner runner;
    private final Path cloneDir;

    public SnapshotTipCheck(GitProcessRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
    }

    /**
     * An interrupted verification found at the task branch's tip, if any.
     *
     * @param taskId the tracker's original taskId; sanitized into the task branch name ({@link
     *     TaskIdSanitizer#branchName})
     * @return the pending verification's attempt commit, round and decision request, or empty when
     *     the tip is not a well-formed snapshot commit
     */
    public Optional<PendingVerification> inspect(String taskId) {
        String branch = TaskIdSanitizer.branchName(taskId);
        GitCommandResult tip = runner.run(cloneDir, "log", "-1", "--format=%H%x00%s", "refs/heads/" + branch);
        if (tip.exitCode() != 0) {
            // Not an alarm — the branch may simply not exist yet — but the resume it silently
            // routes through the ordinary salvage path is worth a trace (FR5).
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.debug(
                    "snapshot-tip check could not read {} (git exited {}): {}",
                    branch,
                    tip.exitCode(),
                    tip.stderr().forLog());
            return Optional.empty();
        }
        // @UntrustedParser warrant (design D11): what leaves the split is a commit id and a commit
        //     subject the factory itself wrote (ServiceCommitMessages), from which only the stage
        //     name, the round number and the round token are lifted — and a malformed subject is
        //     refused below.
        String[] parts = tip.stdout().forParsing().strip().split("\u0000", 2);
        if (parts.length < 2 || !parts[1].startsWith(SUBJECT_PREFIX)) {
            return Optional.empty();
        }
        String rest = parts[1].substring(SUBJECT_PREFIX.length());
        Matcher subject = STAGE_ROUND_TOKEN.matcher(rest);
        if (!subject.matches()) {
            warnMalformed(branch, rest);
            return Optional.empty();
        }
        AttemptKey key;
        try {
            key = new AttemptKey(taskId, subject.group(1), Integer.parseInt(subject.group(2)));
        } catch (IllegalArgumentException e) {
            // A blank stage or a round past int range: the shape matched, the values did not.
            warnMalformed(branch, rest);
            return Optional.empty();
        }
        RoundToken token = RoundToken.of(subject.group(3));
        return Optional.of(
                new PendingVerification(parts[0], key.stage(), key.attempt(), token, request(parts[0], key, token)));
    }

    /**
     * The decision request the snapshot's tree holds at the round's token path, or empty when the
     * round asked nothing — {@code git show <snapshot>:<path>} in the factory clone, the durable
     * medium (ADR 0003), through the package's one {@code git show} seam, which throws rather than
     * answering "absent" for a read cut off before its own exit.
     *
     * <p>@UntrustedParser warrant (design D11): the content leaves raw because it is the
     * decision file itself, handed to the one tolerant reader a live round uses, which mints it as
     * agent text — the same bytes the live read hands over from the box's working copy.
     */
    private Optional<String> request(String snapshot, AttemptKey key, RoundToken token) {
        return new GitShowTip(runner, cloneDir, snapshot)
                .readAtTip(HarvestedBoundaryCheck.decisionPath(key, token))
                .map(UntrustedText::forParsing);
    }

    /**
     * A tip that announces itself as a snapshot but does not carry a readable {@code
     * <stage>#<round> <token>}: the factory wrote it, so this is an anomaly rather than an ordinary
     * tip, and it silently costs the resume its no-attempt-burned re-verification (FR5 of
     * harden-logging-observability). DEBUG because the resume still completes correctly through
     * salvage — nothing is lost, only re-done.
     */
    private static void warnMalformed(String branch, String subjectTail) {
        // throwable-not-subject: the shape is the diagnosis; the parse failure carries nothing more.
        log.debug(
                "snapshot tip of {} carries an unreadable stage#round token, resuming through salvage: {}",
                branch,
                LogText.forLog(subjectTail));
    }
}
