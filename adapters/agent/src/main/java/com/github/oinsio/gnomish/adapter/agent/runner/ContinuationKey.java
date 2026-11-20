package com.github.oinsio.gnomish.adapter.agent.runner;

/**
 * The key a later request continues a round's agent session by (FR9, design D4 of
 * extract-agent-round-runner): an opaque token the dialect read off the stream — the Claude
 * dialect's session id — carried as a {@code decision-request} answer's continuation key and
 * handed back to the same dialect on the {@code start} that carries the reply. Never shown as prose
 * and never parsed by the runner, so it stays a string, as the contract's {@code
 * Continuation.key} does.
 *
 * <p>Implements FR6, FR9 of extract-agent-round-runner.
 *
 * @param value the key; never blank
 */
public record ContinuationKey(String value) {

    public ContinuationKey {
        requireNonBlank(value);
    }

    /**
     * Refuses a blank key: a continuation that names no session cannot be resumed. Kept as an
     * explicit static method rather than inline in the compact constructor: PIT's record filter
     * suppresses all mutations inside a record's canonical constructor.
     */
    private static void requireNonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ContinuationKey.value must not be blank");
        }
    }
}
