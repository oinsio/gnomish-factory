package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead;
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.executorcontract.ResultSource;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a task branch on resume (FR21, design D15): a registered commit carrying the snapshot
 * subject — {@code gnomish: snapshot <stage>#<round> <token>} — means the factory died <em>during
 * verification</em>. The subject is read at the task's registered commit and nowhere else (design
 * D13 of tighten-spec-contract), so a snapshot-shaped subject the gnome commits above the register
 * is not a snapshot. The resuming instance re-verifies exactly that attempt commit and burns no
 * attempt; any other register resumes through the ordinary salvage path.
 *
 * <p>A registered pending commit (design D21 of migrate-verify-to-executor-contract) is the same
 * interrupted verification one step later: its subject names the round as the snapshot's does, and
 * {@link PendingChain} follows first parents over the round's pending commits down to that snapshot.
 * The answer carries both commits — the snapshot the checks judge, the pending commit the round's
 * close sits on — and the round's registered chain the one walk passed (design D14 as amended
 * 2026-11-13). A malformed subject, or a pending commit standing on no snapshot of its round, is
 * refused.
 *
 * <p>The token, with the stage and round, fixes the one path the round's decision request could live
 * at ({@link GnomeRangeInspection#decisionPath}), so a round killed after asking re-raises its question
 * (FR15, NFR-R4 of make-checkpoint-gate-durable); it travels on through {@link RoundToken#of}, reused,
 * never minted, so the round is restored from the record (FR13 of make-checkpoint-gate-durable).
 *
 * <p>Reads only commit subjects and one blob as bare-object queries in the factory clone — no
 * checkout, no hooks (FR17); the subjects are a parsing contract ({@link ServiceCommitMessages#snapshot}).
 *
 * <p>Implements FR21 of add-sandbox-core; FR15 of make-checkpoint-gate-durable; FR3, FR20 of
 * migrate-verify-to-executor-contract.
 */
// Not a record: a behavior-bearing reader over the git seam, like GitShowTip and VerifiedTip.
@SuppressWarnings("ClassCanBeRecord")
@UntrustedParser
public final class SnapshotTipCheck {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTipCheck.class);

    // Single owner: reads ServiceCommitMessages' prefixes rather than repeating the literals, so
    // the writer and this parser cannot drift (manual-sync-pairs.md, preference 1).
    private static final String SNAPSHOT_PREFIX = ServiceCommitMessages.SNAPSHOT_PREFIX;

    private static final String PENDING_PREFIX = ServiceCommitMessages.PENDING_PREFIX;

    /**
     * What follows the prefix: {@code <stage>#<round> <token>}. The stage is everything before the
     * last {@code #}; the token has {@link RoundToken}'s own shape, so a subject the writer could
     * not have produced — the token-less form included — is refused here rather than half-read.
     */
    private static final Pattern STAGE_ROUND_TOKEN = Pattern.compile("(.+)#([0-9]+) ([0-9a-f]+)");

    private final GitRunner runner;
    private final Path cloneDir;
    private final FactoryRegisterRead register;
    private final PendingChain chain;

    public SnapshotTipCheck(GitRunner runner, Path cloneDir) {
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.register = new FactoryRegisterRead(runner);
        this.chain = new PendingChain(runner, cloneDir);
    }

    /**
     * An interrupted verification found at the task's registered commit, if any.
     *
     * @param taskId the tracker's original taskId; sanitized into the task branch name ({@link
     *     TaskIdSanitizer#branchName})
     * @return the pending verification's attempt commit, registered commit, round and decision
     *     request, or empty when the registered commit is neither a well-formed snapshot commit nor a
     *     pending commit standing on one of its round, or there is none
     */
    public Optional<PendingVerification> inspect(String taskId) {
        String branch = TaskIdSanitizer.branchName(taskId);
        Optional<RegisteredCommit> registered = register.registered(cloneDir, taskId);
        if (registered.isEmpty()) {
            // throwable-not-subject: the absence is the classification; nothing was thrown.
            log.debug("snapshot check found no factory register for {}, resuming through salvage", branch);
            return Optional.empty();
        }
        return inspectAt(taskId, branch, registered.get());
    }

    private Optional<PendingVerification> inspectAt(String taskId, String branch, RegisteredCommit registered) {
        GitCommandResult subjectLine = runner.run(cloneDir, "log", "-1", "--format=%s", registered.id());
        if (subjectLine.exitCode() != 0) {
            // Not an alarm, but the salvage it routes the resume through is worth a trace (FR5).
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.debug(
                    "snapshot-tip check could not read {} (git exited {}): {}",
                    branch,
                    subjectLine.exitCode(),
                    subjectLine.stderr().forLog());
            return Optional.empty();
        }
        // @UntrustedParser warrant (design D11): a subject the factory wrote on the registered
        //     commit, of which only stage, round and token are lifted; a malformed one is refused.
        String line = subjectLine.stdout().forParsing().strip();
        if (line.startsWith(SNAPSHOT_PREFIX)) {
            return round(taskId, branch, line.substring(SNAPSHOT_PREFIX.length()))
                    .map(round -> verification(registered, List.of(registered.id()), round));
        }
        if (line.startsWith(PENDING_PREFIX)) {
            return round(taskId, branch, line.substring(PENDING_PREFIX.length()))
                    .flatMap(round -> chain.snapshotBelow(branch, registered, round.key(), round.token())
                            .map(chain -> verification(registered, chain, round)));
        }
        return Optional.empty();
    }

    /** The round a well-formed subject names: its stage and number, and its token. */
    private record Round(AttemptKey key, RoundToken token) {}

    /** The round {@code rest}, a subject past its prefix, names, or empty (traced) when malformed. */
    private static Optional<Round> round(String taskId, String branch, String rest) {
        Matcher subject = STAGE_ROUND_TOKEN.matcher(rest);
        if (!subject.matches()) {
            warnMalformed(branch, rest);
            return Optional.empty();
        }
        try {
            var key = new AttemptKey(taskId, subject.group(1), Integer.parseInt(subject.group(2)));
            return Optional.of(new Round(key, RoundToken.of(subject.group(3))));
        } catch (IllegalArgumentException e) {
            // A blank stage or a round past int range: the shape matched, the values did not.
            warnMalformed(branch, rest);
            return Optional.empty();
        }
    }

    /**
     * The interrupted verification of {@code round} judged at the snapshot that ends {@code chain}, the
     * registered chain from the register down, whose request is read
     * at the register: a pending commit's tree is its snapshot's with only {@code state.json} replaced
     * (its admission refuses any other path), so the round's request path reads the same blob there.
     */
    private PendingVerification verification(RegisteredCommit registered, List<String> chain, Round round) {
        return new PendingVerification(
                chain.getLast(),
                round.key().stage(),
                round.key().attempt(),
                round.token(),
                request(registered, round.key(), round.token()),
                registered.id(),
                chain);
    }

    /**
     * The decision request the registered tree holds at the round's token path, or empty when the
     * round asked nothing — read from the durable medium (ADR 0003) through the package's one {@code
     * git show} seam, which throws rather than answering "absent" for a read cut off before its exit.
     * The blob goes to the same {@link DecisionRequestRead} a live round reads its file through, as
     * the bytes of a {@link ResultSource}, so recovery parses through the live path's read
     * (crash-consistency item 14; FR6 of define-executor-contract).
     *
     * <p>@UntrustedParser warrant (design D11): the content leaves raw because it is the decision
     * file itself, handed to the one tolerant read, which mints it again as agent text.
     */
    private Optional<RoundDecisionRequest> request(RegisteredCommit registered, AttemptKey key, RoundToken token) {
        Optional<byte[]> blob = new GitShowTip(runner, cloneDir, registered)
                .readAtRegister(GnomeRangeInspection.decisionPath(key, token))
                .map(text -> text.forParsing().getBytes(StandardCharsets.UTF_8));
        return DecisionRequestRead.read(limit -> blob.map(bytes -> Arrays.copyOf(bytes, prefixLength(bytes, limit))));
    }

    /** How much of {@code bytes} a read asking for at most {@code limit} of them returns. */
    private static int prefixLength(byte[] bytes, long limit) {
        return (int) Math.min(bytes.length, limit);
    }

    /**
     * A register naming a snapshot or pending commit with no readable {@code <stage>#<round> <token>}:
     * it costs the resume its re-verification (FR5 of harden-logging-observability); DEBUG, since
     * salvage still completes.
     */
    private static void warnMalformed(String branch, String subjectTail) {
        // throwable-not-subject: the shape is the diagnosis; the parse failure carries nothing more.
        log.debug(
                "snapshot tip of {} carries an unreadable stage#round token, resuming through salvage: {}",
                branch,
                LogText.forLog(subjectTail));
    }
}
