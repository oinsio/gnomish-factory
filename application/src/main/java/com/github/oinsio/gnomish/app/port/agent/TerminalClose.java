package com.github.oinsio.gnomish.app.port.agent;

/**
 * How a round's one close ends it (design D11 of extract-agent-round-runner, as amended
 * 2026-11-25): a terminal outcome only. Going {@code pending} is not a close — the round stays open
 * through {@link RoundEnvironmentSource.Round#goPending()} — so {@link
 * RoundEnvironmentSource.Round#closeRound} takes this type and the compiler refuses the pending
 * kind there. The snapshot of the close is named by {@link #snapshot()}, the {@link RoundClose} the
 * one round snapshot step chooses its subject by.
 *
 * <p>Implements FR10 of extract-agent-round-runner; FR19 of migrate-verify-to-executor-contract.
 */
public enum TerminalClose {

    /** The round's executor completed: its snapshot is {@link RoundClose#VERIFIABLE}. */
    VERIFIABLE,

    /** The round's executor did not complete: its snapshot is {@link RoundClose#UNVERIFIABLE}. */
    UNVERIFIABLE;

    /**
     * The snapshot subject this close takes.
     *
     * @return the snapshot's close; never {@link RoundClose#PENDING}
     */
    public RoundClose snapshot() {
        return switch (this) {
            case VERIFIABLE -> RoundClose.VERIFIABLE;
            case UNVERIFIABLE -> RoundClose.UNVERIFIABLE;
        };
    }
}
