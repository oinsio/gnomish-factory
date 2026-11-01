package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.ChildOutput;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The live stdout reader of a {@code program} run whose describe declares {@code streams_progress}
 * (FR5 of define-executor-contract; design D3): in the shape of the agent round's {@code
 * StreamDrain}, it reads the {@link ChildOutput}'s stream view on a virtual thread started at the
 * launch, so the silence bound sees each line as it arrives. Each line is NDJSON progress, logged at
 * DEBUG and never parsed for a result; a line that is not JSON is a contract violation, logged and
 * skipped — the first of a run at WARN, the rest at DEBUG and counted, so a chatty executor does not
 * flood the operator plane.
 *
 * <p>Every chunk read, whole line or not, is a sign of life: {@link #lastActivity()} moves on each.
 * A line is kept to {@link #LINE_CAP_BYTES}; the rest of an overlong line is read and dropped, so no
 * line grows the reader's memory past the cap.
 *
 * <p>Like every reader of a child's output it reads only through the owner's non-parking view and
 * closes no pipe (ADR 0018): the drain ends at the exit with nothing pending, or on {@link #close()},
 * the owner's stop. Its join is bounded by the handle's post-exit bound the caller passes; this class
 * holds no bound of its own ({@code DrainOwnerGateSpec}). The round thread's MDC is applied on the
 * reader's thread, so its lines carry the round's task, stage and attempt.
 *
 * <p>Implements FR5, NFR-O2, NFR-R2 of define-executor-contract.
 */
final class ProgressLines implements AutoCloseable {

    /** The most bytes of one progress line kept. */
    static final int LINE_CAP_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ProgressLines.class);

    private final ChildOutput output;
    private final InstantSource clock;
    private final String executor;
    private final Thread thread;
    private volatile Instant lastActivity;
    private volatile boolean ended;
    private int violations;

    private ProgressLines(ChildOutput output, InstantSource clock, String executor) {
        this.output = output;
        this.clock = clock;
        this.executor = executor;
        this.lastActivity = clock.instant();
        @Nullable Map<String, String> roundMdc = MDC.getCopyOfContextMap();
        this.thread = Thread.ofVirtual().name("program-progress").unstarted(() -> drain(roundMdc));
    }

    /**
     * Starts reading {@code output} at once.
     *
     * @param output the launched program's stdout; never null, read by this reader from here on
     * @param clock the read-time source each chunk is stamped with; never null
     * @param executor the executor's name, for the log lines; never blank
     * @return the running reader; never null
     */
    static ProgressLines start(ChildOutput output, InstantSource clock, String executor) {
        ProgressLines lines = new ProgressLines(output, clock, executor);
        lines.thread.start();
        return lines;
    }

    /** The instant the last bytes arrived, or the start when none have. */
    Instant lastActivity() {
        return lastActivity;
    }

    /** Whether the stream has ended: the exit with nothing pending, the owner's stop, or a failed read. */
    boolean ended() {
        return ended;
    }

    /**
     * Waits up to {@code slice} for the stream to end, returning as soon as it does.
     *
     * @param slice how long to wait; never null, zero for no wait
     * @return whether the stream has ended
     */
    boolean endsWithin(Duration slice) {
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
    boolean await(Duration postExitBound) {
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
     * is closed. The reader calls no listener, so nothing on its thread needs an interrupt.
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
            log.debug(
                    "the progress stream of program executor '{}' failed; the run is judged by its result",
                    executor,
                    e);
        } finally {
            ended = true;
            if (violations > 1) {
                log.debug("program executor '{}' wrote {} non-JSON progress lines", executor, violations);
            }
            MDC.clear();
        }
    }

    private void readLines(InputStream in) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int read;
        while ((read = in.read(chunk)) != -1) {
            lastActivity = clock.instant();
            for (int index = 0; index < read; index++) {
                if (chunk[index] == '\n') {
                    judge(line);
                    line.reset();
                } else if (line.size() < LINE_CAP_BYTES) {
                    line.write(chunk[index]);
                }
            }
        }
        judge(line);
    }

    private void judge(ByteArrayOutputStream bytes) {
        UntrustedText line = UntrustedText.subprocess(bytes.toString(StandardCharsets.UTF_8));
        if (line.isBlank()) {
            return;
        }
        try {
            ExecutorContractJson.reader().readTree(bytes.toByteArray());
            log.debug("program executor '{}' progress: {}", executor, line.forLog());
        } catch (IOException notJson) {
            violations++;
            if (violations == 1) {
                log.warn(
                        OperatorEvent.PROGRAM_PROGRESS_NOT_JSON.head()
                                + "program executor '{}' wrote a progress line that is not JSON, a contract"
                                + " violation; skipped: {}",
                        executor,
                        line.forLog());
            } else {
                log.debug("program executor '{}' wrote another non-JSON progress line: {}", executor, line.forLog());
            }
        }
    }
}
