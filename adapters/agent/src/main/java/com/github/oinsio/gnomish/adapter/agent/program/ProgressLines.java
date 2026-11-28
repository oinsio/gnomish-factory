package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.subprocess.ChildOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The live stdout reader of a run whose output is read line by line while it runs (FR5 of
 * define-executor-contract; design D3; FR1, FR8 of extract-agent-round-runner): it reads the {@link
 * ChildOutput}'s stream view on a virtual thread started at the launch, so the silence bound sees each
 * line as it arrives, and hands every line to its {@link Sink} — a {@code streams_progress} program's
 * progress judgement ({@link ProgressJudge}), or the agent round runner's dialect parse. Both readers
 * of a child's output by line take this one reader, so the line cap, the activity stamp and the stop
 * cannot be spelled twice.
 *
 * <p>Every chunk read, whole line or not, is a sign of life: {@link #lastActivity()} moves on each, and
 * {@link #anyRead()} says whether any byte arrived at all, which the startup-read bound waits on. A
 * line is kept to the cap its caller names; the rest of an overlong line is read and dropped, so no
 * line grows the reader's memory past the cap, and the sink is told the line was cut.
 *
 * <p>Like every reader of a child's output it reads only through the owner's non-parking view and
 * closes no pipe (ADR 0018): the drain ends at the exit with nothing pending, or on {@link #close()},
 * the owner's stop. Its join is bounded by the handle's post-exit bound the caller passes; this class
 * holds no bound of its own ({@code DrainOwnerGateSpec}). The round thread's MDC is applied on the
 * reader's thread, so the sink's lines carry the round's task, stage and attempt.
 *
 * <p>Implements FR5, NFR-O2, NFR-R2 of define-executor-contract; FR1, FR8, NFR-R1 of
 * extract-agent-round-runner.
 */
public final class ProgressLines implements AutoCloseable {

    /**
     * What a reader hands each line to, on the reader's thread, in arrival order. A sink calls nothing
     * that blocks on the round: it runs while the process runs.
     */
    public interface Sink {

        /**
         * One line, without its terminator; the last line of a stream is handed over even when it
         * has no terminator, and even when it is empty.
         *
         * @param line the line's bytes, at most the reader's cap; never null
         * @param cut whether the line was longer than the cap and its rest dropped
         */
        void line(byte[] line, boolean cut);

        /** The stream has ended; called once, on the reader's thread, after the last line. */
        default void ended() {}
    }

    private static final Logger log = LoggerFactory.getLogger(ProgressLines.class);

    private final ChildOutput output;
    private final InstantSource clock;
    private final int lineCapBytes;
    private final Sink sink;
    private final Thread thread;
    private volatile Instant lastActivity;
    private volatile boolean anyRead;
    private volatile boolean ended;

    private ProgressLines(ChildOutput output, InstantSource clock, int lineCapBytes, Sink sink) {
        this.output = output;
        this.clock = clock;
        this.lineCapBytes = lineCapBytes;
        this.sink = sink;
        this.lastActivity = clock.instant();
        @Nullable Map<String, String> roundMdc = MDC.getCopyOfContextMap();
        this.thread = Thread.ofVirtual().name("stdout-lines").unstarted(() -> drain(roundMdc));
    }

    /**
     * Starts reading {@code output} at once.
     *
     * @param output the launched process's stdout; never null, read by this reader from here on
     * @param clock the read-time source each chunk is stamped with; never null
     * @param lineCapBytes the most bytes of one line kept; positive
     * @param sink what each line is handed to; never null
     * @return the running reader; never null
     */
    public static ProgressLines start(ChildOutput output, InstantSource clock, int lineCapBytes, Sink sink) {
        ProgressLines lines = new ProgressLines(output, clock, lineCapBytes, sink);
        lines.thread.start();
        return lines;
    }

    /** The instant the last bytes arrived, or the start when none have. */
    public Instant lastActivity() {
        return lastActivity;
    }

    /** Whether any byte has arrived since the start. */
    public boolean anyRead() {
        return anyRead;
    }

    /** Whether the stream has ended: the exit with nothing pending, the owner's stop, or a failed read. */
    public boolean ended() {
        return ended;
    }

    /**
     * Waits up to {@code slice} for the stream to end, returning as soon as it does.
     *
     * @param slice how long to wait; never null, zero for no wait
     * @return whether the stream has ended
     */
    public boolean endsWithin(Duration slice) {
        if (!ended && !slice.isZero()) {
            try {
                thread.join(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ended;
    }

    /**
     * Waits up to {@code postExitBound} for the reader to finish after the process ended.
     *
     * @param postExitBound the handle's post-exit bound; never null
     * @return whether the reader finished within it; a reader still running is stopped
     */
    public boolean await(Duration postExitBound) {
        try {
            if (thread.join(postExitBound)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
        return false;
    }

    /**
     * Stops the reader: the owner's stop, which the view's next read observes within a tick; no pipe
     * is closed. The reader calls nothing that waits on the round, so nothing on its thread needs an
     * interrupt.
     */
    @Override
    public void close() {
        output.stop();
    }

    private void drain(@Nullable Map<String, String> roundMdc) {
        if (roundMdc != null) {
            MDC.setContextMap(roundMdc);
        }
        try (InputStream in = output.stream()) {
            readLines(in);
        } catch (IOException e) {
            log.debug("a stdout stream read line by line failed; the run is judged by what it left", e);
        } finally {
            ended = true;
            sink.ended();
            MDC.clear();
        }
    }

    private void readLines(InputStream in) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean cut = false;
        int read;
        while ((read = in.read(chunk)) != -1) {
            lastActivity = clock.instant();
            anyRead = true;
            for (int index = 0; index < read; index++) {
                if (chunk[index] == '\n') {
                    sink.line(line.toByteArray(), cut);
                    line.reset();
                    cut = false;
                } else if (line.size() < lineCapBytes) {
                    line.write(chunk[index]);
                } else {
                    cut = true;
                }
            }
        }
        sink.line(line.toByteArray(), cut);
    }
}
