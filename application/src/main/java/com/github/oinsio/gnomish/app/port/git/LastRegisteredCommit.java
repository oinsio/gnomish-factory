package com.github.oinsio.gnomish.app.port.git;

import java.util.List;
import java.util.Optional;

/**
 * The read-only view of a run's round cell that the working-copy check of a {@code verdict} or
 * {@code decision} run asks (design D21 of migrate-verify-to-executor-contract, "The lag the write
 * leaves behind", as amended by review r2 of 2026-11-12): the round's last registered commit — its
 * snapshot, or the last pending commit landed on it — which names the one move of {@code HEAD}
 * during such a run that is the factory's. The move is judged by this identity, never by the
 * content between two commits (crash-consistency item 13). It also answers the round's registered
 * chain, which the adoption of a recorded key and the host round close ({@code PendingStateLag})
 * read for the {@code state.json} lag a pending commit leaves (design D14 as amended 2026-11-13):
 * the chain was walked once, on the factory's side, by the resume's own walk, and is never walked
 * again where either reader looks (crash-consistency item 14).
 *
 * <p>{@link CurrentRound} implements it; {@link #NONE}, the local default, holds no commit and is
 * what a run with no round cell passes — the in-place run and the conformance kit's candidate — so
 * any move of {@code HEAD} there is refused.
 *
 * <p>Implements FR20 of migrate-verify-to-executor-contract.
 */
public interface LastRegisteredCommit {

    /** The local default: no round, no registered commit, so no move of {@code HEAD} is the factory's. */
    LastRegisteredCommit NONE = new LastRegisteredCommit() {
        @Override
        public Optional<String> lastRegistered() {
            return Optional.empty();
        }

        @Override
        public List<String> registeredChain() {
            return List.of();
        }
    };

    /**
     * The round's last registered commit at the moment of the call.
     *
     * @return its full object id, or empty when the round has none (no round opened, or no snapshot)
     */
    Optional<String> lastRegistered();

    /**
     * The round's registered commits at the moment of the call: the last registered one first, then
     * each first parent down to the round's snapshot, which is last.
     *
     * @return the chain, empty when the round has nothing registered; never null
     */
    List<String> registeredChain();
}
