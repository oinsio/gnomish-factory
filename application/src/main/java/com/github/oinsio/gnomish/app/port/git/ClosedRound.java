package com.github.oinsio.gnomish.app.port.git;

/**
 * A round that has its snapshot (design D10 of make-checkpoint-gate-durable): the identity the
 * round opened under and the harvested attempt commit that closed it, always together. A round
 * with a snapshot and no token is not a value this type can hold, so a consumer of the closed
 * round — the sandboxed persistence's carve-out, diff base and parent check; the check runners'
 * attempt commit — can never be handed half of one. Read from {@link CurrentRound#closed()}.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable.
 *
 * @param token the identity the round opened under; never null
 * @param attemptCommit the harvested snapshot (attempt) commit id that closed the round; never null
 */
public record ClosedRound(RoundToken token, String attemptCommit) {}
