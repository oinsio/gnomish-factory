package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.RoundToken
import java.nio.file.Path

/**
 * A round cell holding the round a {@link SandboxRoundEnvironmentSource#openRound} on {@code branch}
 * right now would open — the branch's tip in {@code cloneDir}, exactly the token the round source
 * mints — for the specs that drive {@link EnvironmentRoundSnapshot} and {@link
 * EnvironmentAttemptPersistence} without a round source (design D10 of
 * make-checkpoint-gate-durable).
 */
final class OpenedRound {

    private OpenedRound() {}

    /** A fresh cell with a round open at {@code branch}'s current tip. */
    static CurrentRound at(Path cloneDir, String branch) {
        reopen(new CurrentRound(), cloneDir, branch)
    }

    /** Opens the next round in {@code rounds} at {@code branch}'s current tip, as a new round would. */
    static CurrentRound reopen(CurrentRound rounds, Path cloneDir, String branch) {
        rounds.open(RoundToken.of(
                        new GitProcessRunner().run(cloneDir, 'rev-parse', 'refs/heads/' + branch).stdout().forParsing().trim()))
        rounds
    }
}
