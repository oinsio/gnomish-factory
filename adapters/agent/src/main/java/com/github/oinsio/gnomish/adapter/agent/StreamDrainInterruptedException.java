package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;

/**
 * Thrown when the round thread is interrupted while waiting for its stdout
 * drain to finish (FR2 of fix-round-stdout-drain). It is the drain's second
 * infrastructure failure, deliberately distinct from {@link
 * StreamDrainTimeoutException}: nothing here says the tail-drain grace was too
 * short — the wait was cut short by whoever interrupted the round, and the
 * drain may have been about to finish — so the message must not send an
 * operator off raising {@code factory.agent-cli-tail-drain-grace}.
 *
 * <p>Unchecked, and classified exactly like its sibling: the executor lets it
 * propagate into {@code RoundOutcome.CannotExecute} (no stage attempt burned,
 * NFR-R2), while the judge — which never throws (design D5 of
 * add-agent-executor) — maps it to a {@code CannotVerify} vote.
 *
 * <p>Implements FR2, NFR-R2 of fix-round-stdout-drain.
 */
public final class StreamDrainInterruptedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param bytesRead how many raw stdout bytes the drain had consumed when the wait was cut short
     */
    public StreamDrainInterruptedException(long bytesRead) {
        super("interrupted while waiting for the agent stdout drain to finish after process exit (" + bytesRead
                + " bytes read)");
    }
}
