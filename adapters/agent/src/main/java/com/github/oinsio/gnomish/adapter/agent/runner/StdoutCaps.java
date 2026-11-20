package com.github.oinsio.gnomish.adapter.agent.runner;

/**
 * The caps on what an agent round's stdout may cost the factory (FR1, FR8, NFR-R1 of
 * extract-agent-round-runner): the line cap — the most bytes of one line kept, the rest of an overlong
 * line read and dropped, and the capped prefix parsed — and the retained cap — the most bytes of
 * lines whose events the round keeps for its dialect. Past the retained cap the round keeps the
 * events of its first half and of its latest half, and drops those in between, so the history a
 * dialect reads its session from and the final result it reads its answer from both survive, and the
 * heap stays bounded however long the CLI talks.
 *
 * <p>The retained cap is at least twice the line cap, so the line just read always fits the latest
 * half: no line's events are dropped as they are kept.
 *
 * <p>Implements FR1, FR8, NFR-R1 of extract-agent-round-runner.
 *
 * @param lineBytes the most bytes of one line kept; positive
 * @param retainedBytes the most bytes of lines whose events are kept; at least twice {@code lineBytes}
 */
public record StdoutCaps(int lineBytes, long retainedBytes) {

    /** The factory's caps: lines of 1 MiB, 32 MiB of lines kept. */
    public static final StdoutCaps DEFAULTS = new StdoutCaps(1024 * 1024, 32L * 1024 * 1024);

    public StdoutCaps {
        check(lineBytes, retainedBytes);
    }

    /**
     * Refuses a non-positive line cap and a retained cap under two lines. Kept as an explicit static
     * method rather than inline in the compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor.
     */
    private static void check(int lineBytes, long retainedBytes) {
        if (lineBytes < 1) {
            throw new IllegalArgumentException("StdoutCaps.lineBytes must be positive, was " + lineBytes);
        }
        if (retainedBytes < 2L * lineBytes) {
            throw new IllegalArgumentException("StdoutCaps.retainedBytes must be at least twice lineBytes, was "
                    + retainedBytes + " for lines of " + lineBytes);
        }
    }
}
