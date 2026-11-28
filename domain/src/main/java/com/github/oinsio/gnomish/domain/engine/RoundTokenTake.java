package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one take of a round's token (FR14, FR21 of define-executor-contract, design D13): {@link
 * RoundIdentity} yields a round's token once, after the executor returned, and a round that went
 * {@code pending} needs it earlier — its pending record names the round by it before the first poll
 * (design D11 of extract-agent-round-runner) — and again for its record when it closes. Taken at the
 * first ask, kept for the next, so the record and the pending entry carry one token by construction.
 *
 * <p>A resuming run's pending round (design D11, "Resume") is judged here before any request: its
 * entry is the one {@link PendingEntries#roundEntry} accepts against the identity's restored pending
 * token, and only a continued round takes its token before a request, so a round that is not
 * continued takes its token after its {@code start} as before.
 *
 * <p>One instance per round, confined to the thread that runs it.
 *
 * <p>Implements FR14, FR21 of define-executor-contract; FR10, NFR-R4 of extract-agent-round-runner.
 */
final class RoundTokenTake {

    private final RoundIdentity rounds;
    private boolean taken;
    private @Nullable RoundToken token;

    /** @param rounds the run's round identity, asked at most once; never null */
    RoundTokenTake(RoundIdentity rounds) {
        this.rounds = rounds;
    }

    /**
     * The entry of the pending round the run continues, read off the registered commit's list: the
     * open round's {@code Round} entry, judged against the identity's restored pending token. It
     * takes nothing: the round's wait takes the token through {@link #token()} when it continues an
     * accepted entry, and a round with no such entry takes it after its {@code start}, as before.
     *
     * @param entries the list read off the registered commit, {@code TaskState.pendingPolls()}
     * @param attempt the round's attempt number
     * @return the round's entry, or empty when the round is not a pending one to continue
     */
    Optional<PendingPollRecord> resumedEntry(List<PendingPollRecord> entries, int attempt) {
        return PendingEntries.roundEntry(entries, attempt, rounds.pendingToken().orElse(null));
    }

    /**
     * The round's token: taken from the identity on the first call, the same value on every later one.
     *
     * @return the round's token, or {@code null} in the in-place run
     */
    @Nullable
    RoundToken token() {
        if (!taken) {
            token = rounds.takeToken().orElse(null);
            taken = true;
        }
        return token;
    }
}
