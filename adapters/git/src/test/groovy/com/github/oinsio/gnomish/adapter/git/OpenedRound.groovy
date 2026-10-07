package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path

/**
 * A round-token ref holding the token a round opened on {@code branch} right now would get — the
 * branch's tip in {@code cloneDir}, exactly what {@link SandboxRoundEnvironmentSource#openRound}
 * records — for the specs that drive {@link EnvironmentRoundSnapshot} without a round source.
 */
final class OpenedRound {

    private OpenedRound() {}

    static RoundTokenRef at(Path cloneDir, String branch) {
        def ref = new RoundTokenRef()
        ref.record(new RoundToken(
                        new GitProcessRunner().run(cloneDir, 'rev-parse', 'refs/heads/' + branch).stdout().forParsing().trim()))
        ref
    }
}
