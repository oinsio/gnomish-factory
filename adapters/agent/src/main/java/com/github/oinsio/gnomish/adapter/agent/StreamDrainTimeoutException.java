package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when a round's stdout drain is still reading after the post-exit bound
 * of the round's handle expired (FR2 of fix-round-stdout-drain; FR19, design D17
 * as amended 2026-10-17 of fix-terminal-receipt-convergence): the process has
 * exited, only the tail still in the pipe remained to be read within the bound,
 * and it was not. That is an infrastructure failure of the round — never a
 * silently partial event list — so the message names the bound that expired and
 * how much of the stream had been read. The bound is the handle's, not an
 * operator setting, so the message advises raising nothing.
 *
 * <p>Unchecked, like {@link MissingResultEventException} and {@link
 * RoundTimeoutException}: the executor lets it propagate into {@code
 * RoundOutcome.CannotExecute} (no stage attempt burned, NFR-R2), while the
 * judge — which never throws (design D5 of add-agent-executor) — maps it to a
 * {@code CannotVerify} vote.
 *
 * <p>Implements FR2, NFR-R2, D2 of fix-round-stdout-drain; FR19 of
 * fix-terminal-receipt-convergence.
 */
public final class StreamDrainTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param postExitBound the handle's post-exit bound that expired; never null
     * @param bytesRead how many raw stdout bytes the drain had consumed by then
     */
    public StreamDrainTimeoutException(Duration postExitBound, long bytesRead) {
        super("agent stdout drain did not finish within the post-exit bound " + postExitBound + " after process exit ("
                + bytesRead + " bytes read)");
    }
}
