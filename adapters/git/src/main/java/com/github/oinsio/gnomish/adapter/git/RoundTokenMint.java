package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.nio.file.Path;

/**
 * The one minting of a fresh round's {@link RoundToken} in a branch mode (FR21 of
 * define-executor-contract; design D7 of extract-agent-round-runner as amended): it reads the tip
 * the round opens on, refuses a failed resolution through {@link VerifiedTip#required} — a round
 * never opens under nothing — parses it through {@link RoundToken#of} and opens the round in the
 * run's {@link CurrentRound}. Its two callers differ only in where the tip lives: the host git-mode
 * decoration {@link MidRoundPushRounds} reads the task worktree's {@code HEAD}, the box source
 * {@link SandboxRoundEnvironmentSource} the task branch in the factory clone. A resumed round never
 * comes here: it restores the token its record carries ({@link CurrentRound#restore}).
 *
 * <p>Replaces the declared pair the two callers kept by hand, each with its own copy of the steps
 * (preference 1 of the manual-sync-pairs rule: both ends share {@code adapters/git}).
 *
 * <p>Implements FR21 of define-executor-contract; M1 of extract-agent-round-runner.
 */
final class RoundTokenMint {

    private final GitRunner runner;
    private final CurrentRound rounds;

    /**
     * @param runner the git subprocess runner the tip is resolved through; never null
     * @param rounds the run's round cell each minted token is opened in; never null
     */
    RoundTokenMint(GitRunner runner, CurrentRound rounds) {
        this.runner = runner;
        this.rounds = rounds;
    }

    /**
     * Opens a fresh round on the tip {@code revision} names in {@code repository}.
     *
     * @param repository the working copy or clone the tip is resolved in
     * @param revision the revision naming the tip the round opens on
     * @return the opened round's token, read back from the cell
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if the tip
     *     cannot be resolved; no round is opened then
     */
    RoundToken open(Path repository, String revision) {
        String tip = VerifiedTip.required(revision, "rev-parse", runner.run(repository, "rev-parse", revision));
        rounds.open(RoundToken.of(tip));
        return rounds.opened();
    }
}
