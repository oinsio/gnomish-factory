package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.subprocess.DrainSink;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/**
 * The retention policy of a command check's output tail: the last {@link #MAX_TAIL_LINES} lines,
 * capped at {@link #MAX_TAIL_BYTES} bytes, fed chunk by chunk by the drain that reads the stream
 * (design D6, FR7 of add-manual-run; design D17 of fix-terminal-receipt-convergence).
 *
 * <p>A line ends where a line reader ends it — at a newline, a carriage return, or the two together
 * — and is decoded from its own bytes once it ends, so a character split across two reads is kept
 * whole. An unterminated last line is kept when the stream ended there, never when it was cut.
 *
 * <p>The retained lines are kept under this object's monitor because the drain thread appends while
 * the check thread snapshots: a join that let go at its bound still yields a consistent prefix
 * (UX4 of bound-subprocess-commands).
 *
 * <p>Implements FR7, D6 of add-manual-run; FR19 of fix-terminal-receipt-convergence.
 */
final class TailLines implements DrainSink {

    /** ~200 lines OR ~10 KB, whichever is hit first (design D6, FR7). */
    private static final int MAX_TAIL_LINES = 200;

    private static final int MAX_TAIL_BYTES = 10 * 1024;

    private final Deque<String> lines = new ArrayDeque<>();

    /** The current line's bytes; touched by the drain thread only. */
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Whether the last byte was a carriage return, so a newline right after it ends no second line. */
    private boolean afterCarriageReturn;

    private int bytes;

    @Override
    public void keep(byte[] chunk, int length) {
        for (int i = 0; i < length; i++) {
            byte b = chunk[i];
            if (b == '\n' && afterCarriageReturn) {
                afterCarriageReturn = false;
            } else if (b == '\n' || b == '\r') {
                endLine();
                afterCarriageReturn = b == '\r';
            } else {
                pending.write(b);
                afterCarriageReturn = false;
            }
        }
    }

    @Override
    public void end() {
        if (pending.size() > 0) {
            endLine();
        }
    }

    /** The retained lines, joined by newlines. */
    synchronized String snapshot() {
        return String.join("\n", lines);
    }

    private void endLine() {
        String line = pending.toString(StandardCharsets.UTF_8);
        pending.reset();
        append(line);
    }

    /**
     * Appends one line and evicts from the front until both bounds hold again — the natural way to
     * keep "last N lines up to a byte cap" without buffering the whole stream first.
     */
    private synchronized void append(String line) {
        lines.addLast(line);
        bytes += lineBytes(line);
        while (lines.size() > MAX_TAIL_LINES || bytes > MAX_TAIL_BYTES) {
            bytes -= lineBytes(requireEvicted(lines.pollFirst()));
        }
    }

    private static int lineBytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + 1;
    }

    /**
     * Asserts the just-evicted line is non-null: every entry into the loop above requires {@code
     * lines.size() > MAX_TAIL_LINES} (so the deque is non-empty) or {@code bytes > MAX_TAIL_BYTES}
     * ({@code bytes} only grows when a line is added, so a positive running total also implies a
     * non-empty deque) — {@code pollFirst()} on a non-empty deque never returns {@code null}.
     * Isolated to its own method so the provably unreachable null case has nowhere for a mutant to
     * hide as a false SURVIVED.
     *
     * <p>PIT documented exception ({@code testing.md}, provably equivalent mutant): {@code
     * @DoNotMutate} — the line-count and byte-cap invariant is otherwise fully covered by
     * CommandProcessRunnerSpec's boundary specs.
     */
    @DoNotMutate
    private static String requireEvicted(@Nullable String evicted) {
        if (evicted == null) {
            throw new IllegalStateException("unreachable: loop guard implies a non-empty deque");
        }
        return evicted;
    }
}
