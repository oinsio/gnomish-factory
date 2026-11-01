package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;

/**
 * Where the engine takes the identity of the round it is about to record (design D13 of
 * define-executor-contract): the round token the round source minted when the round opened, or —
 * on the resume path — restored from the round's durable record. The engine stamps it on the
 * round's {@link com.github.oinsio.gnomish.domain.engine.AttemptRecord} and never derives it; the
 * round source fills it, and the engine only reads it, once per recorded round, after the executor
 * returned.
 *
 * <p>Two realizations, each chosen at its own composition site and none defaulted: the run's
 * current-round cell in a branch mode (host git, container), which yields the open round's token,
 * and {@link #inPlace()} for the in-place run, which has no task branch and so no token (the
 * {@code cli/manual-run} scenario "In-place mode needs no git"). {@code EnginePorts} takes this port
 * with no default, so a branch-mode wiring cannot forget it into a tokenless one.
 *
 * <p>Implements FR14, FR21 of define-executor-contract.
 */
@FunctionalInterface
public interface RoundIdentity {

    /**
     * The token of the round the engine is recording, taken once per recorded round.
     *
     * @return the round's token; empty only for the in-place identity
     * @throws IllegalStateException in a branch mode, when no round was opened, or when the open
     *     round's token was already taken for an earlier record — a round that opened no token of
     *     its own would otherwise carry the previous round's
     */
    Optional<RoundToken> takeToken();

    /**
     * The in-place run's identity: no task branch, so no round token is minted or recorded. Named
     * so the in-place composition site says so explicitly.
     *
     * @return the identity that yields no token; never null
     */
    static RoundIdentity inPlace() {
        return Optional::empty;
    }
}
