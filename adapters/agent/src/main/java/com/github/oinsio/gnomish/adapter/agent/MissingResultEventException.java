package com.github.oinsio.gnomish.adapter.agent;

import java.io.Serial;

/**
 * Thrown when a round's stream-json output carries no {@link
 * AgentEvent.ResultEvent}: the result event is essential (design D3, FR4) — a
 * verdict on the round cannot exist without it, so its absence is an
 * infrastructure failure of the round, not a quality failure (NFR-R1). A
 * malformed result-event <em>line</em> never reaches this point at all: {@link
 * StreamJsonParser} silently drops any line it cannot map to a known {@link
 * AgentEvent} variant (task 3.1, FR4), so "missing" and "unparseable" collapse
 * into the same observable symptom here — no {@code ResultEvent} in the parsed
 * list — and this single exception covers both.
 *
 * <p>Unchecked, following this codebase's established idiom for
 * infrastructure-failure signaling: {@code RoundExecution.execute} catches any
 * {@link RuntimeException} the {@link
 * com.github.oinsio.gnomish.domain.engine.port.StageExecutor} port throws and
 * shapes it into {@code RoundOutcome.CannotExecute}, burning no stage attempt
 * (NFR-R1). The eventual {@code CliStageExecutor} (task 6.5) is expected to let
 * this propagate uncaught from its {@code execute()} call.
 *
 * <p>The message carries the round's read volume — raw bytes and parsed events
 * — whenever the caller knows it (FR5, UX2, D5 of fix-round-stdout-drain), and
 * names probable stream truncation when that volume sits at an OS pipe-buffer
 * boundary. Since the continuous drain landed, truncation should be impossible;
 * the hint is a tripwire for a regression or an exotic environment, and the
 * volume alone is what lets a human tell "the agent emitted no result" apart
 * from "the stream was cut short" without reading adapter source.
 *
 * <p>Implements FR4, NFR-R1, NFR-R2, D3 of add-agent-executor; FR5, UX2, D5 of
 * fix-round-stdout-drain.
 */
public final class MissingResultEventException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The pipe buffer a truncated stream would have filled: 64 KiB on both macOS
     * and Linux, the platforms this factory runs on.
     */
    private static final long PIPE_BUFFER_BYTES = 65_536L;

    /**
     * How far below a pipe-buffer multiple still counts as "at the boundary": one
     * {@link java.io.BufferedReader} buffer, since a truncated stream is cut
     * wherever the last full read landed rather than exactly on the multiple (the
     * observed repro stopped at 65 528 of 65 536 bytes).
     */
    private static final long BOUNDARY_TOLERANCE_BYTES = 8_192L;

    /** Passed as {@code bytesRead} when the caller has no byte accounting to report. */
    static final long UNKNOWN_VOLUME = -1L;

    /**
     * @param sessionId the round's session id read from the init event, or a
     *     placeholder describing the round when even the init event is absent;
     *     never null, folded into the exception message for diagnosability
     */
    public MissingResultEventException(String sessionId) {
        this(sessionId, UNKNOWN_VOLUME, 0);
    }

    /**
     * @param sessionId as above
     * @param bytesRead the raw stdout bytes the round's drain consumed, or {@link
     *     #UNKNOWN_VOLUME} when the caller kept no byte accounting
     * @param eventCount how many stream-json events were parsed out of them
     */
    public MissingResultEventException(String sessionId, long bytesRead, int eventCount) {
        super("stream-json carried no result event for round (session: " + sessionId + ")"
                + volume(bytesRead, eventCount) + truncationHint(bytesRead));
    }

    private static String volume(long bytesRead, int eventCount) {
        if (bytesRead < 0) {
            return "";
        }
        return " — read " + bytesRead + " bytes, " + eventCount + " event(s)";
    }

    /**
     * The truncation hint, when {@code bytesRead} sits within one buffered read
     * below a 64 KiB multiple (D5). Byte-volume proximity is the heuristic rather
     * than a trailing partial JSON line: a stream can be cut exactly at a line
     * boundary, so a clean last line proves nothing.
     */
    private static String truncationHint(long bytesRead) {
        if (bytesRead <= 0) {
            return "";
        }
        long distanceToBoundary = (PIPE_BUFFER_BYTES - bytesRead % PIPE_BUFFER_BYTES) % PIPE_BUFFER_BYTES;
        if (distanceToBoundary > BOUNDARY_TOLERANCE_BYTES) {
            return "";
        }
        return "; that volume sits at a " + PIPE_BUFFER_BYTES
                + "-byte pipe-buffer boundary, so the stream was probably truncated rather than result-less";
    }
}
