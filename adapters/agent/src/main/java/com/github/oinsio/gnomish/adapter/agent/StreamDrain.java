package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The round's stdout gobbler (design D1 of fix-round-stdout-drain): reads and
 * parses the agent process's stream-json output on a dedicated virtual thread
 * started at launch, concurrently with the running process, so the OS pipe
 * never fills — a full pipe either blocks a synchronous writer until the
 * {@code roundTimeout} kill or, with a CLI that exits via {@code
 * process.exit()}, discards the stream's tail, which is exactly where the
 * essential result event lives (FR1).
 *
 * <p>Both round executions share this one implementation (FR6). The round
 * thread keeps ownership of the round budget: it waits for process exit as
 * before, then calls {@link #await(Duration)} to collect the events within the
 * tail-drain grace. {@link #close()} on every exit path guarantees no drain
 * thread and no open stream outlives the round (NFR-R1), which is why callers
 * hold it in a try-with-resources.
 *
 * <p>Listener callbacks fire from this thread, per line, while the process is
 * still running (FR4, D4). The round thread's MDC — the attempt's {@code
 * taskId}/{@code stage}/{@code attempt} keys, thread-local by nature — is
 * captured at construction and applied inside the drain thread, so live
 * progress lines land in the same log scope they did when they were emitted
 * post-exit from the round thread.
 *
 * <p>Implements FR1, FR2, FR4, FR6, NFR-R1, NFR-O1, NFR-P1, D1, D3, D4, D5 of
 * fix-round-stdout-drain.
 */
final class StreamDrain implements AutoCloseable {

    /**
     * How long {@link #close()} waits for an interrupted drain thread to notice.
     * Not the tail-drain grace: by the time close runs, either the drain already
     * finished (the normal path, where this join returns at once) or the round is
     * being torn down by a failure and the stream has just been closed underneath
     * it — this only keeps the teardown from returning while the thread is still
     * unwinding.
     */
    private static final Duration CLOSE_JOIN = Duration.ofSeconds(1);

    private final InputStream source;
    private final CountingInputStream counting;
    private final List<TimestampedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Thread thread;

    private volatile @Nullable RuntimeException failure;

    /**
     * Starts draining {@code output} immediately — the returned drain is already
     * reading by the time this call returns.
     *
     * @param output the launched process's stdout; never null, owned by the drain
     *     from here on
     * @param clock the read-time source stamped onto each event as its line is
     *     consumed, now genuinely the line's arrival time (NFR-O1); never null
     * @param progressListener the live-progress subscriber invoked from the drain
     *     thread, per line; never null
     * @return the running drain; never null
     */
    static StreamDrain start(InputStream output, Clock clock, AgentProgressListener progressListener) {
        return new StreamDrain(output, clock, progressListener);
    }

    private StreamDrain(InputStream output, Clock clock, AgentProgressListener progressListener) {
        this.source = output;
        this.counting = new CountingInputStream(output);
        var parser = new StreamJsonParser(clock, progressListener);
        Map<String, String> roundMdc = MDC.getCopyOfContextMap();
        this.thread = Thread.ofVirtual().name("agent-stdout-drain").unstarted(() -> drain(parser, roundMdc));
        this.thread.start();
    }

    /**
     * The events parsed from the whole stream, once the drain has finished within
     * {@code grace} (FR2). Called by the round thread after the process exited:
     * the tail is already in the pipe buffer, so the join is normally instant.
     *
     * @param grace the tail-drain grace ({@code factory.agent-cli-tail-drain-grace});
     *     never null, always positive
     * @return the round's events, in wire order; never null, possibly empty
     * @throws StreamDrainTimeoutException if the drain is still reading when the
     *     grace expires — an infrastructure failure of the round, never a silently
     *     partial stream
     * @throws StreamDrainInterruptedException if the round thread is interrupted
     *     before the drain finishes; the grace is blameless on that path, so it is a
     *     separate failure rather than a timeout that would misdirect the operator
     * @throws UncheckedIOException if the drain failed on a genuine I/O error while
     *     the process was alive (D3)
     */
    List<TimestampedEvent> await(Duration grace) {
        Join join = joinWithin(grace);
        if (join != Join.FINISHED) {
            close();
            throw join == Join.INTERRUPTED
                    ? new StreamDrainInterruptedException(bytesRead())
                    : new StreamDrainTimeoutException(grace, bytesRead());
        }
        RuntimeException drainFailure = failure;
        if (drainFailure != null) {
            throw drainFailure;
        }
        return List.copyOf(events);
    }

    /** The raw stdout bytes consumed so far — the missing-result diagnostic's volume figure (D5). */
    long bytesRead() {
        return counting.count();
    }

    /**
     * Ends the drain on any exit path (NFR-R1): interrupts the thread, closes the
     * process's stdout — which is what unblocks a read still parked on a killed
     * process's pipe — and waits briefly for the thread to unwind. Idempotent, and
     * a no-op in effect on the normal path where the drain already reached EOF and
     * closed the stream itself.
     */
    @Override
    public void close() {
        thread.interrupt();
        closeSource();
        joinWithin(CLOSE_JOIN);
    }

    /**
     * The drain thread's body. A {@code roundTimeout} kill closes the pipe
     * mid-read, so the {@link IOException} it raises is recorded rather than
     * thrown: the round thread never consults it on that path — it throws its
     * timeout classification first (D3, FR3) — and on any path where it does
     * consult it, the process was alive and the failure is genuine.
     */
    private void drain(StreamJsonParser parser, @Nullable Map<String, String> roundMdc) {
        applyMdc(roundMdc);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(counting, StandardCharsets.UTF_8))) {
            parser.parseInto(reader, events);
        } catch (UncheckedIOException e) {
            failure = new UncheckedIOException("could not read agent process stdout", e.getCause());
        } catch (IOException e) {
            failure = new UncheckedIOException("could not read agent process stdout", e);
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            MDC.clear();
        }
    }

    /** D4: the round's log scope, thread-local, re-established on the thread that now emits into it. */
    private void applyMdc(@Nullable Map<String, String> roundMdc) {
        if (roundMdc != null) {
            MDC.setContextMap(roundMdc);
        }
    }

    /** How a join ended — the three outcomes {@link #await} classifies differently. */
    private enum Join {
        FINISHED,
        EXPIRED,
        INTERRUPTED
    }

    private Join joinWithin(Duration timeout) {
        try {
            return thread.join(timeout) ? Join.FINISHED : Join.EXPIRED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Join.INTERRUPTED;
        }
    }

    private void closeSource() {
        try {
            source.close();
        } catch (IOException e) {
            // Teardown only: the round's own outcome is already decided by here.
        }
    }
}
