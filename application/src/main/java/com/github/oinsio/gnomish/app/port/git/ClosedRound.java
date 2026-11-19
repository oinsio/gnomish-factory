package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.domain.engine.RoundToken;

/**
 * A round that has its snapshot (design D10 of make-checkpoint-gate-durable): the identity the
 * round opened under and the harvested attempt commit that closed it, always together. A round
 * with a snapshot and no token is not a value this type can hold, so a consumer of the closed
 * round — the persistences' carve-out, range base and parent; the check runners' attempt commit —
 * can never be handed half of one. Read from {@link CurrentRound#closed()}.
 *
 * <p>Two commits, two readers (design D21, D22 of migrate-verify-to-executor-contract): {@code
 * attemptCommit} is the snapshot, the tree every check judges; {@code registered} is the round's
 * last registered commit, the parent of the round's close and the base of its range. They are equal
 * after the snapshot and after a restore; only a pending commit moves {@code registered}.
 *
 * <p>Implements FR13, FR15 of make-checkpoint-gate-durable; FR19 of
 * migrate-verify-to-executor-contract.
 *
 * @param token the identity the round opened under; never null
 * @param attemptCommit the snapshot (attempt) commit id that closed the round; never null
 * @param registered the round's last registered commit; never null
 * @param close how the round closed: the subject its snapshot carries, which a persist re-admitting
 *     the snapshot judges it by; never null
 */
public record ClosedRound(RoundToken token, String attemptCommit, String registered, RoundClose close) {}
