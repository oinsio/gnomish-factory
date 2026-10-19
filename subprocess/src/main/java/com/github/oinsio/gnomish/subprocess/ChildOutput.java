package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The readable side of a child's output pipe, minted only from a started {@link Process}, and read
 * without ever parking in the pipe (design D1, D2 of make-pipe-reads-non-parking). The value holds
 * the process beside its stream, so the stop rule is wired by construction: every step first asks
 * whether the child is alive, then how many bytes the pipe reports, reads exactly those, and when
 * there are none waits a tick on its own thread — 1 ms after bytes, doubling while idle, capped at
 * 5 ms — a tick the child's exit ends at once. The first query that finds the child exited and
 * nothing pending ends the reading, with no tick after it. {@link #stop()} ends it within a tick,
 * and so does an interrupt of the wait.
 *
 * <p>Asking about the exit <b>before</b> the query means a byte the child wrote before its exit is
 * always seen by the query that follows: the JDK reports the exit only after it reaped the child, so
 * the child's own writes are all in the pipe by then; after the JDK's reaper swapped the stream for its buffer of
 * stragglers, the query reads that buffer. No thread ever holds the stream's monitor across a wait,
 * so the reaper is never blocked, and the factory closes no pipe of a child: the reaper closes it.
 *
 * <p>The steps exist once ({@link #next}) and are offered in two forms: the push form
 * ({@link #pushTo}, run on a drain's own thread, each chunk handed to a {@link DrainSink}) and the
 * pull form, the stream view ({@link #stream()}), whose {@code read} runs the same steps on the
 * caller's thread and whose {@code close()} is {@link #stop()}. One reader at a time: the tick is
 * the reading thread's state.
 *
 * <p>The wait is a bounded wait for the child's exit ({@link Process#onExit}, obtained once per
 * value), like {@link Process#waitFor} with a timeout, not a sleep (design D2 as amended
 * 2026-10-19): this module may take no {@code Sleeper} (ADR 0014), and the tick is a property of the
 * pipe. {@code waitFor} itself is not used, so the drain never takes a wait-return step a spec placed
 * for the supervisor's wait on {@code FakeProcess}.
 *
 * <p>Implements FR1, FR2, FR3, NFR-P1, NFR-P2, NFR-R1, NFR-R3 of make-pipe-reads-non-parking.
 */
public final class ChildOutput {

    /** The wait between two queries; a role interface so a spec records the ticks instead of timing them. */
    @FunctionalInterface
    interface Tick {
        void pause(long millis) throws InterruptedException;
    }

    /** The end of the stream: the child exited and nothing is pending, or the pipe reported its end. */
    static final int END = -1;

    private static final int STOPPED = -2;
    private static final int CHUNK = 8192;
    private static final long FIRST_TICK_MILLIS = 1;
    private static final long LAST_TICK_MILLIS = 5;

    private final Process process;
    private final InputStream stream;
    private final Tick tick;
    private final InputStream view = new View();
    private volatile boolean stopped;
    private long pause = FIRST_TICK_MILLIS;

    ChildOutput(Process process, InputStream stream, Tick tick) {
        this.process = process;
        this.stream = stream;
        this.tick = tick;
    }

    /**
     * The child's standard output.
     *
     * @param process the started child
     * @return its stdout, read without parking
     */
    public static ChildOutput stdout(Process process) {
        return new ChildOutput(process, process.getInputStream(), untilExit(process));
    }

    /**
     * The child's standard error.
     *
     * @param process the started child
     * @return its stderr, read without parking
     */
    public static ChildOutput stderr(Process process) {
        return new ChildOutput(process, process.getErrorStream(), untilExit(process));
    }

    /** Ends the reading within a tick, in either form; nothing is closed. Idempotent. */
    public void stop() {
        stopped = true;
    }

    /**
     * The pull form: a stream whose {@code read} runs the non-parking steps on the caller's thread,
     * returns {@code -1} after the exit with nothing pending or after {@link #stop()}, and whose
     * {@code close()} is {@link #stop()}.
     *
     * @return the view; the same instance on every call
     */
    public InputStream stream() {
        return view;
    }

    /**
     * The push form, run on the drain's own thread: hands every chunk to {@code target}, and calls
     * {@link DrainSink#end()} only when the stream really ended, never after a stop or an interrupt.
     *
     * @param target where each chunk goes
     * @throws IOException if the stream failed, which ends the push without {@code end()}
     */
    void pushTo(DrainSink target) throws IOException {
        byte[] chunk = new byte[CHUNK];
        int read = next(chunk, 0, chunk.length);
        while (read != END && read != STOPPED) {
            target.keep(chunk, read);
            read = next(chunk, 0, chunk.length);
        }
        if (read == END) {
            target.end();
        }
    }

    /** The steps: a count of bytes read into {@code buffer}, {@link #END}, or {@link #STOPPED}. */
    private int next(byte[] buffer, int offset, int length) throws IOException {
        while (!stopped) {
            boolean exited = !process.isAlive();
            int available = stream.available();
            if (available > 0) {
                pause = FIRST_TICK_MILLIS;
                return stream.read(buffer, offset, Math.min(available, length));
            }
            if (exited) {
                return END;
            }
            if (!rest()) {
                return STOPPED;
            }
        }
        return STOPPED;
    }

    /** Waits the current tick and doubles it up to the cap; false when the wait was interrupted. */
    private boolean rest() {
        try {
            tick.pause(pause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        pause = Math.min(pause * 2, LAST_TICK_MILLIS);
        return true;
    }

    /**
     * The production tick: waits up to the tick's length for the child's exit, so an exit during
     * the tick ends it at once. The exit's future is obtained here, once per value.
     *
     * @param process the started child
     * @return the tick of a value minted from {@code process}
     */
    static Tick untilExit(Process process) {
        CompletableFuture<Process> exit = process.onExit();
        return millis -> {
            try {
                exit.get(millis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException tickEnded) {
                // A timeout is the ordinary end of a tick. The exit's future, never handed out, completes
                // with the process; were it to fail, the tick still ends and the next step asks the process.
            }
        };
    }

    /** The pull form over {@link #next}. */
    private final class View extends InputStream {

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == END ? END : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            int read = next(bytes, offset, length);
            return read == STOPPED ? END : read;
        }

        @Override
        public void close() {
            stop();
        }
    }
}
