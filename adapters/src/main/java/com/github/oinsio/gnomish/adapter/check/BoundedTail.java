package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A command check's merged stdout/stderr stream, read on a virtual thread of its own while the
 * command still runs, retaining only the last {@link Tail#MAX_TAIL_LINES} lines capped at {@link
 * Tail#MAX_TAIL_BYTES} bytes (design D6, FR7 of add-manual-run).
 *
 * <p>Reading the stream to its end on the calling thread — the shape this class replaces — is what
 * let a hung check hang the run: a command that never exits never closes its stdout, so the read
 * never returned and the wait below it was never reached. The drain therefore starts with the
 * process and is joined, bounded, once the wait has resolved. The bound applies whichever way the
 * command ended: by then the command is over and only the pipe's remaining bytes are left to read,
 * unless a process that escaped the tree still holds the pipe open — and no verdict may wait on
 * that (design D2, D12).
 *
 * <p>The retained lines are kept under their own monitor because the drain thread appends while the
 * check thread snapshots: a bounded join that expires still yields a consistent prefix — the tail
 * captured so far — rather than nothing at all (UX4).
 *
 * <p>Implements FR7, D6 of add-manual-run; FR2, FR12, UX4 of bound-subprocess-commands.
 */
final class BoundedTail {

    private final CompletableFuture<Void> drain;
    private final Tail tail;

    private BoundedTail(CompletableFuture<Void> drain, Tail tail) {
        this.drain = drain;
        this.tail = tail;
    }

    /**
     * Starts draining {@code stream} into a bounded tail on a named virtual thread.
     *
     * @param stream the process's merged output stream; closed when the drain ends
     * @return the live tail; never null
     */
    static BoundedTail start(InputStream stream) {
        Tail tail = new Tail();
        CompletableFuture<Void> drain = CompletableFuture.runAsync(
                () -> tail.readFrom(stream),
                command -> Thread.ofVirtual().name("check-output-tail").start(command));
        return new BoundedTail(drain, tail);
    }

    /**
     * Waits for the drain, bounded, and returns the tail it captured. The wait is the supervisor's
     * own uninterruptible pair — {@link CompletableFuture#completeOnTimeout} for the bound, {@link
     * CompletableFuture#join()} for the wait — so a check interrupted mid-run still reports the
     * output it had, and the interrupt flag the caller above still has to see is never consumed
     * here.
     *
     * @param bound how long to wait for the drain before returning what it has; never null
     * @return the captured tail, or the prefix captured so far if the bound expired; never null
     */
    String join(Duration bound) {
        drain.completeOnTimeout(null, bound.toMillis(), TimeUnit.MILLISECONDS).join();
        return tail.snapshot();
    }

    /** The retained lines and their running byte total, shared between the drain and the joiner. */
    private static final class Tail {

        /** ~200 lines OR ~10 KB, whichever is hit first (design D6, FR7). */
        private static final int MAX_TAIL_LINES = 200;

        private static final int MAX_TAIL_BYTES = 10 * 1024;

        private final Deque<String> lines = new ArrayDeque<>();

        private int bytes;

        private void readFrom(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                }
            } catch (IOException e) {
                // Stream read failure mid-command — including the closed pipe of a killed process:
                // keep whatever tail was captured; the termination says what happened.
            }
        }

        /**
         * Appends one line and evicts from the front until both bounds hold again — the natural way
         * to keep "last N lines up to a byte cap" without buffering the whole stream first
         * (relevant for long-running or chatty commands).
         */
        private synchronized void append(String line) {
            lines.addLast(line);
            bytes += lineBytes(line);
            while (lines.size() > MAX_TAIL_LINES || bytes > MAX_TAIL_BYTES) {
                bytes -= lineBytes(requireEvicted(lines.pollFirst()));
            }
        }

        private synchronized String snapshot() {
            return String.join("\n", lines);
        }

        private static int lineBytes(String line) {
            return line.getBytes(StandardCharsets.UTF_8).length + 1;
        }

        /**
         * Asserts the just-evicted line is non-null: every entry into the loop above requires
         * {@code lines.size() > MAX_TAIL_LINES} (&ge; 0, so the deque is non-empty) or {@code bytes
         * > MAX_TAIL_BYTES} ({@code bytes} only grows when a line is added, so a positive running
         * total also implies a non-empty deque) — {@code pollFirst()} on a non-empty deque never
         * returns {@code null}. Isolated to its own method (rather than a defensive {@code
         * if}/{@code break} inline) so the provably-unreachable null case has nowhere for a mutant
         * to hide as a false SURVIVED.
         *
         * <p>PIT M4 documented exception (build.gradle has the full rationale): {@code
         * @DoNotMutate} — this line-count/byte-cap invariant is otherwise fully covered by
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
}
