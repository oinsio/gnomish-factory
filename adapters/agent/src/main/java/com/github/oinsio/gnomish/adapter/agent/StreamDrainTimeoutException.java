package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when a round's stdout drain is still reading after the tail-drain
 * grace expired (FR2, D2 of fix-round-stdout-drain): the process has exited,
 * the already-piped tail should have been absorbed within the grace, and it was
 * not. That is an infrastructure failure of the round — never a silently
 * partial event list — so the message names the grace that expired and how much
 * of the stream had been read, the two figures an operator needs to decide
 * whether to raise {@code factory.agent-cli-tail-drain-grace}.
 *
 * <p>Unchecked, like {@link MissingResultEventException} and {@link
 * RoundTimeoutException}: the executor lets it propagate into {@code
 * RoundOutcome.CannotExecute} (no stage attempt burned, NFR-R2), while the
 * judge — which never throws (design D5 of add-agent-executor) — maps it to a
 * {@code CannotVerify} vote.
 *
 * <p>Implements FR2, NFR-R2, D2 of fix-round-stdout-drain.
 */
public final class StreamDrainTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param grace the tail-drain grace that expired; never null
     * @param bytesRead how many raw stdout bytes the drain had consumed by then
     */
    public StreamDrainTimeoutException(Duration grace, long bytesRead) {
        super("agent stdout drain did not finish within the tail-drain grace " + grace + " after process exit ("
                + bytesRead + " bytes read); raise factory.agent-cli-tail-drain-grace if this recurs");
    }
}
