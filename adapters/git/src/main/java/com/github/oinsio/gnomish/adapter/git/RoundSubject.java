package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord;
import com.github.oinsio.gnomish.domain.engine.PendingSubject;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The round a factory subject names — {@code <prefix><stage>#<round> <token>}, for the snapshot, the
 * unverified snapshot, the pending-round snapshot and the pending subjects: the one owner of that wire
 * form, its prefixes, its rendering and its parse side by side (crash-consistency item 14), so the
 * pickup reads a round's identity exactly as the live path wrote it, with its token reused through
 * {@link RoundToken#of}, never minted (FR13 of make-checkpoint-gate-durable; FR11 of
 * extract-agent-round-runner, design D11, "Resume").
 *
 * <p>Unlike the other subjects of {@link ServiceCommitMessages}, these <em>are</em> a parsing
 * contract: resume classifies a registered commit by them ({@link SnapshotTipCheck}), which is the only
 * way to tell a factory snapshot from the gnome's own in-box commits (both carry the in-box identity),
 * and {@link PendingChain} compares a commit's subject with the writer's own spelling of its round.
 *
 * <p>The stage is everything before the last {@code #}; the token has {@link RoundToken}'s own shape,
 * so a tail the writer could not have produced — the token-less form included — is refused rather
 * than half-read. A refusal is the caller's to trace: it knows the branch.
 *
 * <p>Implements FR21 of add-sandbox-core; FR15 of make-checkpoint-gate-durable; FR19, FR20 of
 * migrate-verify-to-executor-contract; FR10, FR11 of extract-agent-round-runner.
 *
 * @param key the round's task, stage and number
 * @param token the round's token
 */
public record RoundSubject(AttemptKey key, RoundToken token) {

    /** The snapshot subject's prefix, of a round that closed verifiable (design D15 of add-sandbox-core). */
    static final String SNAPSHOT_PREFIX = ServiceCommitMessages.PREFIX + "snapshot ";

    /** The pending subject's prefix (design D21 of migrate-verify-to-executor-contract). */
    static final String PENDING_PREFIX = ServiceCommitMessages.PREFIX + "pending ";

    /** The pending-round snapshot subject's prefix (design D11 of extract-agent-round-runner). */
    static final String PENDING_SNAPSHOT_PREFIX = ServiceCommitMessages.PREFIX + "pending-snapshot ";

    private static final String UNVERIFIED_SNAPSHOT_PREFIX = ServiceCommitMessages.PREFIX + "unverified-snapshot ";

    private static final Pattern STAGE_ROUND_TOKEN = Pattern.compile("(.+)#([0-9]+) ([0-9a-f]+)");

    /**
     * The snapshot subject of a round that closed verifiable, on either medium: {@code gnomish: snapshot
     * <stage>#<round> <token>} (FR21 of add-sandbox-core; FR15 of make-checkpoint-gate-durable, design
     * D10; design D22 of migrate-verify-to-executor-contract). The token names the round that wrote the
     * snapshot, so the resume reads its answer file at exactly that round's path ({@link
     * com.github.oinsio.gnomish.domain.branch.EnvelopePaths#answerFile}).
     *
     * @param stage the stage id the round belongs to
     * @param round the attempt key's non-negative number, as the round's record carries it
     * @param token the round's token, from the run's {@link com.github.oinsio.gnomish.app.port.git.CurrentRound} — never re-read here
     * @return the formatted commit message
     */
    public static String snapshot(String stage, int round, RoundToken token) {
        return SNAPSHOT_PREFIX + tail(stage, round, token);
    }

    /**
     * The snapshot subject of a round whose executor did not complete: {@code gnomish:
     * unverified-snapshot <stage>#<round> <token>} (design D22 of migrate-verify-to-executor-contract).
     * Outside {@link #SNAPSHOT_PREFIX}, so {@link SnapshotTipCheck} never reads it as an interrupted
     * verification; inside the service namespace, so a gnome commit carrying it is a violation ({@link
     * GnomeRangeInspection}). Parameters as {@link #snapshot}.
     */
    public static String unverifiedSnapshot(String stage, int round, RoundToken token) {
        return UNVERIFIED_SNAPSHOT_PREFIX + tail(stage, round, token);
    }

    /**
     * The snapshot subject of a {@code transform} round that answered {@code pending}, under its pending
     * round commit: {@code gnomish: pending-snapshot <stage>#<round> <token>} (design D11 of
     * extract-agent-round-runner). {@link SnapshotTipCheck} never reads it as a round to verify: a
     * pending commit above it is a pending round, and it alone a round to re-drive. Parameters as
     * {@link #snapshot}.
     */
    public static String pendingSnapshot(String stage, int round, RoundToken token) {
        return PENDING_SNAPSHOT_PREFIX + tail(stage, round, token);
    }

    /**
     * The pending subject of an open round, on either medium: {@code gnomish: pending <stage>#<round>
     * <token>} (design D21 of migrate-verify-to-executor-contract). The stage comes from the persisted
     * state's position — a pending entry carries none — and the round and token from the list's last
     * entry, the check whose {@code pending} answer the write lands.
     *
     * @param stage the stage the open round belongs to, {@code Position.AtStage.name()}
     * @param last the last entry of the open round's list
     * @return the formatted commit message
     * @throws IllegalArgumentException if the entry names no round token — the in-place run, which
     *     writes no commit
     */
    public static String pending(String stage, PendingPollRecord last) {
        RoundToken token = last.token();
        if (token == null) {
            throw new IllegalArgumentException(
                    "a pending commit names its round's token; the entry of " + entryName(last) + " carries none");
        }
        return pending(stage, last.attempt(), token);
    }

    /** The pending subject of the round {@code stage}, {@code round} and {@code token} name — the one rendering. */
    static String pending(String stage, int round, RoundToken token) {
        return PENDING_PREFIX + tail(stage, round, token);
    }

    /** The entry's subject as a refusal names it: the check by its index, or the round. */
    static String entryName(PendingPollRecord entry) {
        return switch (entry.subject()) {
            case PendingSubject.Check(CheckRef ref) -> "check " + ref.index();
            case PendingSubject.Round _ -> "the round";
        };
    }

    /**
     * The round {@code rest} names.
     *
     * @param taskId the task the subject's branch belongs to
     * @param rest a subject past its prefix, the text the factory wrote and nothing else
     * @return the round, or empty when {@code rest} is not the writer's shape or its values are not a
     *     round's (a blank stage, a round past {@code int} range)
     */
    static Optional<RoundSubject> parse(String taskId, String rest) {
        Matcher subject = STAGE_ROUND_TOKEN.matcher(rest);
        if (!subject.matches()) {
            return Optional.empty();
        }
        try {
            var key = new AttemptKey(taskId, subject.group(1), Integer.parseInt(subject.group(2)));
            return Optional.of(new RoundSubject(key, RoundToken.of(subject.group(3))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The tail every round subject carries past its prefix, the one {@link #parse} reads back. */
    private static String tail(String stage, int round, RoundToken token) {
        return stage + "#" + round + " " + token.commit();
    }
}
