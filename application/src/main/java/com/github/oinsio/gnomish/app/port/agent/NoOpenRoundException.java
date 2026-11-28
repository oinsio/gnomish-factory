package com.github.oinsio.gnomish.app.port.agent;

/**
 * A poll reached a round source that holds no open round and whose run's round cell was not
 * restored with one (design D11, D14 of extract-agent-round-runner, as amended 2026-11-25): a poll
 * continues the round its {@code start} opened and never opens one, so there is nothing to poll in.
 * Thrown by {@link RoundEnvironmentSource#continueRound} before anything is minted or materialized;
 * the round wrapper turns it into the poll's {@code failed/infrastructure} result naming the missing
 * round.
 *
 * <p>Implements FR10 of extract-agent-round-runner.
 */
public final class NoOpenRoundException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * @param stage the stage whose round the poll asked for; never null
     * @param attempt the attempt the poll asked for
     */
    public NoOpenRoundException(String stage, int attempt) {
        super("no round of stage '" + stage + "' attempt " + attempt
                + " is open to poll in: the round source holds none and the run's round cell was not"
                + " restored with a pending round");
    }
}
