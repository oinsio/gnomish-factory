package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The infrastructure-outage signal of a {@link Tracker} write: the tracker could not be reached
 * (network error, sustained 5xx outside the adapter's own transient-retry budget), so the write did
 * NOT land and a later retry may succeed. A terminal-write retry loop ({@code TerminalWriteRetry})
 * retries exactly this exception and gives up bounded; any other {@link RuntimeException} is a
 * non-retryable fault (a bug, a rejected request) that must surface immediately rather than loop
 * (FR10, NFR-R3 of add-claim-heartbeat).
 *
 * <p>This is the port-level marker adapters raise for an unreachable-tracker write; adapter-specific
 * write exceptions (e.g. the GitHub adapter's structural-comment POST failure) extend it, so core
 * classifies "retryable outage vs non-retryable fault" against this single port type without ever
 * naming a concrete adapter class (tracker-port boundary).
 *
 * <p>Implements FR10, NFR-R3 of add-claim-heartbeat.
 */
public class TrackerUnavailableException extends RuntimeException {

    /**
     * @param message a human-readable description of the outage; never null
     */
    public TrackerUnavailableException(String message) {
        super(message);
    }
}
