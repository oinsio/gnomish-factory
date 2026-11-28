package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.agent.RoundHold;
import com.github.oinsio.gnomish.app.port.git.ClosedRound;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;

/**
 * The pending round a branch-mode round source continues on a resuming instance (design D11, D14 of
 * extract-agent-round-runner, as amended 2026-11-25): the round the run's cell was restored with,
 * when its last snapshot is the pending-round snapshot ({@link RoundClose#PENDING}), and only while
 * the source has never held a round of its own — a source that held one and let it go continues
 * nothing, so a closed round is never adopted again. Its token is the cell's, never minted: the two
 * callers, {@link MidRoundPushRounds} and {@link SandboxRoundEnvironmentSource}, build the round over
 * it where {@link RoundTokenMint} would mint one for a {@code start}.
 *
 * <p>Implements FR10, FR11 of extract-agent-round-runner.
 */
final class RestoredPendingRound {

    private RestoredPendingRound() {}

    /**
     * The token of the restored pending round a poll may continue.
     *
     * @param hold the source's hold of its open round; never null
     * @param rounds the run's round cell; never null
     * @return the round's token, or empty when there is none to continue; never null
     */
    static Optional<RoundToken> token(RoundHold<?> hold, CurrentRound rounds) {
        if (hold.everHeld() || rounds.lastRegistered().isEmpty()) {
            return Optional.empty();
        }
        ClosedRound round = rounds.snapshot().orElseThrow();
        return round.close() == RoundClose.PENDING ? Optional.of(round.token()) : Optional.empty();
    }
}
