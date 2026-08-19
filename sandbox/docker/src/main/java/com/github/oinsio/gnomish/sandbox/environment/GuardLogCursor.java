package com.github.oinsio.gnomish.sandbox.environment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Derives the next daemon-side read cursor from a {@code docker logs
 * --timestamps} output (design D3 of fix-denial-report-attachment). The guard
 * container outlives the rounds of a task's lease, so denial read-back is a
 * delta read: each read starts where the previous one ended, and a round's
 * report carries only its own denials.
 *
 * <p>The daemon prefixes every line with an RFC-3339 nanosecond timestamp of its
 * own clock, which is why the cursor is immune to in-box clock skew. {@code
 * docker logs --since} is inclusive, so the cursor is the last line's timestamp
 * advanced by one nanosecond — the smallest value that excludes the line already
 * read without skipping a later one.
 *
 * <p>Best-effort like the read it serves (NFR-R1): output with no parseable
 * timestamp — an empty log, a truncated line — yields {@code null}, meaning
 * "leave the cursor where it is" rather than a failure or a silent reset.
 */
final class GuardLogCursor {

    private GuardLogCursor() {}

    /**
     * The cursor for the next read of a guard log, or {@code null} to keep the
     * current one.
     *
     * @param timestampedStdout the raw {@code docker logs --timestamps} output; never null
     * @return an RFC-3339 instant one nanosecond past the last timestamped line,
     *     or {@code null} when the output carries no parseable timestamp
     */
    static @Nullable String advance(String timestampedStdout) {
        String[] lines = timestampedStdout.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            Instant stamp = timestampOf(lines[i]);
            if (stamp != null) {
                return stamp.plusNanos(1).toString();
            }
        }
        return null;
    }

    /**
     * Whether the read came back filling its {@code --tail} window. {@code docker
     * logs --tail} keeps the <em>newest</em> lines, so a full window means older
     * lines of the same window were dropped by the daemon before anything parsed
     * them — and the cursor still advances past them, making the loss permanent.
     * Silence would render that indistinguishable from a quiet round (NFR-O1).
     *
     * @param timestampedStdout the raw {@code docker logs} output; never null
     * @param tailLines the {@code --tail} cap the read asked for
     * @return true when the output holds at least {@code tailLines} lines
     */
    static boolean saturated(String timestampedStdout, int tailLines) {
        return timestampedStdout.split("\n").length >= tailLines;
    }

    /**
     * The leading RFC-3339 timestamp of one log line, or null when the line
     * carries none. Branch-free on purpose: a "does the line have a space"
     * conditional only spawns boundary mutants that are behaviorally equivalent
     * here (a line with no leading timestamp fails the parse either way), which
     * the mutation gate cannot kill.
     */
    private static @Nullable Instant timestampOf(String line) {
        try {
            return Instant.parse(line.split(" ", 2)[0]);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
