package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.List;

/**
 * One round's snapshot as the {@link RoundSnapshot} step hands it to its medium: the round, the
 * identity it opened under, the subject the step chose by the round's close, and what the round has
 * registered before this snapshot.
 *
 * @param key the round's task, stage and number; never null
 * @param token the round's token, from the run's cell; never null
 * @param subject the snapshot's subject; never blank
 * @param chain the round's registered chain as the cell holds it — the last registered commit first,
 *     its pending-round snapshot last — empty for a round that registered nothing; never null
 */
record SnapshotOrder(AttemptKey key, RoundToken token, String subject, List<String> chain) {

    /**
     * The commit the round's gnome range starts above (design D11 of extract-agent-round-runner, as
     * amended 2026-11-25): the round's last registered commit — the last pending round commit of a
     * round that went pending, the only parent its closing snapshot has — or, for a round that
     * registered nothing, the tip it opened on. A range from the open tip would hold the factory's
     * own pending-round snapshot and pending round commits.
     */
    String base() {
        return base(chain, token);
    }

    /**
     * The one rule of a round's base, for the snapshot and for the host round close alike ({@link
     * GitAttemptPersistence}): the last registered commit of {@code chain}, or the tip {@code token}
     * opened on when the round registered nothing.
     */
    static String base(List<String> chain, RoundToken token) {
        return chain.isEmpty() ? token.commit() : chain.getFirst();
    }
}
