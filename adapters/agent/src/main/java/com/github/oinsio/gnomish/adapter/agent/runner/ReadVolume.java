package com.github.oinsio.gnomish.adapter.agent.runner;

/**
 * What an agent round read from its CLI's stdout — the bytes of the lines the reader delivered, the
 * line breaks between them included, and the events the dialect parsed out of them — and how a broken round's
 * reason names it (FR5, UX2, D5 of fix-round-stdout-drain; carried onto the runner by task 5.1 of
 * extract-agent-round-runner when the old round path left): the volume alone is what lets a human
 * tell "the agent emitted no result" from "the stream was cut short" without reading adapter source,
 * and a volume that sits just below a pipe-buffer multiple is named as probable truncation. The
 * counts are written on the reader's one thread and read only after the reader was joined.
 *
 * <p>Implements FR4 of add-agent-executor; FR5, UX2, D5 of fix-round-stdout-drain.
 */
final class ReadVolume {

    /** The pipe buffer a truncated stream would have filled: 64 KiB on macOS and Linux alike. */
    static final long PIPE_BUFFER_BYTES = 65_536L;

    /**
     * How far below a pipe-buffer multiple still counts as "at the boundary": one buffered read, since
     * a cut stream ends wherever the last full read landed (the observed repro stopped at 65 528 of
     * 65 536 bytes).
     */
    static final long BOUNDARY_TOLERANCE_BYTES = 8_192L;

    private long bytes;
    private long lines;
    private long events;

    /**
     * Counts one delivered line. The reader delivers a line at every line break and one more, possibly
     * empty, at the end of the stream, so the breaks read are one fewer than the lines delivered.
     *
     * @param lineBytes the line's bytes as delivered, without its line break
     * @param parsedEvents the events the dialect parsed out of it
     */
    void line(int lineBytes, int parsedEvents) {
        bytes += lineBytes;
        lines++;
        events += parsedEvents;
    }

    /**
     * The clause a broken round's reason ends with: the volume, and the truncation hint when the
     * volume sits within one buffered read below a 64 KiB multiple. Byte proximity is the heuristic
     * rather than a trailing partial line, since a stream can be cut exactly at a line break.
     *
     * @return the clause, starting with its separator; never null
     */
    String clause() {
        long read = bytes + Math.max(0, lines - 1);
        String volume = " — read " + read + " bytes, " + events + " event(s)";
        long distance = (PIPE_BUFFER_BYTES - read % PIPE_BUFFER_BYTES) % PIPE_BUFFER_BYTES;
        if (read == 0 || distance > BOUNDARY_TOLERANCE_BYTES) {
            return volume;
        }
        return volume + "; that volume sits at a " + PIPE_BUFFER_BYTES
                + "-byte pipe-buffer boundary, so the stream was probably truncated rather than result-less";
    }
}
