package com.github.oinsio.gnomish.app.workspace.fake

import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.RoundToken

/**
 * Builds the run's round cell in the state verification reads it in: a round opened under a fixed
 * token and closed by the snapshot {@code sha} (design D10 of make-checkpoint-gate-durable). Specs
 * that hand an attempt commit to a check runner, a judge-box source or a delivery seam need only
 * the closed round's attempt commit; the token is a placeholder no such consumer reads.
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
final class ClosedRounds {

    /** The placeholder identity every round built here opened under. */
    static final RoundToken TOKEN = RoundToken.of('0a1b2c3d4e5f60718293a4b5c6d7e8f901234567')

    private ClosedRounds() {
    }

    /** A cell whose current round is closed by {@code sha}. */
    static CurrentRound at(String sha) {
        def rounds = new CurrentRound()
        rounds.open(TOKEN)
        rounds.snapshotted(sha)
        rounds
    }
}
