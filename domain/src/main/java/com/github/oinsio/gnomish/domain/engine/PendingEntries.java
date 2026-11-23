package com.github.oinsio.gnomish.domain.engine;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one judgement of whether a {@link PendingPollRecord} read off the registered commit is the
 * open round's own (crash-consistency items 13 and 14; design D21 of
 * migrate-verify-to-executor-contract, D11 of extract-agent-round-runner): its {@code token} equals
 * the token of the round that commit holds open and its {@code attempt} is that round's number. The
 * token is the identity — the attempt number restarts by design and names nothing on its own — and
 * the list's presence decides nothing: a carried-over entry is never continued, whatever its
 * subject. With no open token (the in-place run, which has no branch and no resume) no entry is the
 * round's.
 *
 * <p>Both subjects are judged by this one rule: {@link PendingRound} keeps a verification's own
 * {@code Check} entries by it, and {@link #roundEntry} accepts a {@code transform} round's {@code
 * Round} entry by it — a {@code Round} entry naming another round is refused and never polled.
 *
 * <p>Implements FR20 of migrate-verify-to-executor-contract; FR10, NFR-R3 of
 * extract-agent-round-runner.
 */
final class PendingEntries {

    private PendingEntries() {}

    /**
     * Whether {@code entry} belongs to the open round named by {@code attempt} and {@code open}.
     *
     * @param entry an entry read off the registered commit; never null
     * @param attempt the open round's attempt number
     * @param open the open round's token, or {@code null} in the in-place run
     * @return {@code true} only when both the token and the attempt are the open round's
     */
    static boolean ofRound(PendingPollRecord entry, int attempt, @Nullable RoundToken open) {
        return open != null && open.equals(entry.token()) && entry.attempt() == attempt;
    }

    /**
     * The open round's own {@code Round} entry, the one a pickup continues with {@code poll} instead
     * of a {@code start}. A {@code Round} entry of another round — another token, or another attempt
     * under the same token — is refused: it is never returned, so it is never polled. {@code Check}
     * entries are a verification's, never a round's.
     *
     * @param entries the list read off the registered commit, {@code TaskState.pendingPolls()}
     * @param attempt the open round's attempt number
     * @param open the open round's token, or {@code null} in the in-place run
     * @return the round's entry, or empty when the list holds none of this round
     */
    static Optional<PendingPollRecord> roundEntry(
            List<PendingPollRecord> entries, int attempt, @Nullable RoundToken open) {
        return entries.stream()
                .filter(entry -> isRound(entry.subject()))
                .filter(entry -> ofRound(entry, attempt, open))
                .findFirst();
    }

    private static boolean isRound(PendingSubject subject) {
        return switch (subject) {
            case PendingSubject.Round _ -> true;
            case PendingSubject.Check _ -> false;
        };
    }
}
