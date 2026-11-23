package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import org.jspecify.annotations.Nullable;

/**
 * The one take of a round's token (FR14, FR21 of define-executor-contract, design D13): {@link
 * RoundIdentity} yields a round's token once, after the executor returned, and a round that went
 * {@code pending} needs it earlier — its pending record names the round by it before the first poll
 * (design D11 of extract-agent-round-runner) — and again for its record when it closes. Taken at the
 * first ask, kept for the next, so the record and the pending entry carry one token by construction.
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
