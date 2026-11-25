package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The round a factory subject names past its prefix — {@code <stage>#<round> <token>}, as {@link
 * ServiceCommitMessages} renders the snapshot, the pending-round snapshot and the pending subjects: the
 * one parse of that tail (crash-consistency item 14), so the pickup reads a round's identity exactly as
 * the live path wrote it, with its token reused through {@link RoundToken#of}, never minted (FR13 of
 * make-checkpoint-gate-durable; FR11 of extract-agent-round-runner, design D11, "Resume").
 *
 * <p>The stage is everything before the last {@code #}; the token has {@link RoundToken}'s own shape,
 * so a tail the writer could not have produced — the token-less form included — is refused rather
 * than half-read. A refusal is the caller's to trace: it knows the branch.
 *
 * @param key the round's task, stage and number
 * @param token the round's token
 */
record RoundSubject(AttemptKey key, RoundToken token) {

    private static final Pattern STAGE_ROUND_TOKEN = Pattern.compile("(.+)#([0-9]+) ([0-9a-f]+)");

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
}
