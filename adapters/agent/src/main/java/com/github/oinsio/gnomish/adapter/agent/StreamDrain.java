package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.subprocess.ChildOutput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
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
 * post-exit bound of its handle. {@link #close()} on every exit path guarantees no drain
 * thread and no pending read outlives the round (NFR-R1), which is why callers
 * hold it in a try-with-resources.
 *
 * <p>The drain reads the {@link ChildOutput}'s stream view, which never parks in the pipe
 * (design D1, D5 of make-pipe-reads-non-parking): it reads only the bytes the pipe reports,
 * waits a tick when there are none, and ends as soon as it sees the exit with nothing pending,
 * or on the owner's stop. The drain closes no pipe — the JDK's reaper closes it at the exit — so
 * its close is that stop, observed by the view's next read within a tick on Linux as on macOS.
 *
 * <p>Listener callbacks fire from this thread, per line, while the process is
 * still running (FR4, D4). The round thread's MDC — the attempt's {@code
 * taskId}/{@code stage}/{@code attempt} keys, thread-local by nature — is
 * captured at construction and applied inside the drain thread, so live
 * progress lines land in the same log scope they did when they were emitted
 * post-exit from the round thread.
 *
 * <p>Implements FR1, FR2, FR4, FR6, NFR-R1, NFR-O1, NFR-P1, D1, D3, D4, D5 of
 * fix-round-stdout-drain; FR19, D17 of fix-terminal-receipt-convergence; FR6, D5 of
 * make-pipe-reads-non-parking.
 */
final class StreamDrain implements AutoCloseable {

    private final ChildOutput source;
    private final CountingInputStream counting;
    private final List<TimestampedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Thread thread;

    private volatile @Nullable RuntimeException failure;

    /**
     * Starts draining {@code output} immediately — the returned drain is already
     * reading by the time this call returns.
     *
     * @param output the launched process's stdout, read without parking; never null,
     *     owned by the drain from here on
     * @param clock the read-time source stamped onto each event as its line is
     *     consumed, now genuinely the line's arrival time (NFR-O1); never null
     * @param progressListener the live-progress subscriber invoked from the drain
     *     thread, per line; never null
     * @return the running drain; never null
     */
    static StreamDrain start(ChildOutput output, InstantSource clock, AgentProgressListener progressListener) {
        return new StreamDrain(output, clock, progressListener);
    }

    private StreamDrain(ChildOutput output, InstantSource clock, AgentProgressListener progressListener) {
        this.source = output;
        this.counting = new CountingInputStream(output.stream());
        var parser = new StreamJsonParser(clock, progressListener);
        Map<String, String> roundMdc = MDC.getCopyOfContextMap();
        this.thread = Thread.ofVirtual().name("agent-stdout-drain").unstarted(() -> drain(parser, roundMdc));
        this.thread.start();
    }

    /**
     * The events parsed from the whole stream, once the drain has finished within
     * {@code postExitBound} (FR2). Called by the round thread after the process exited:
     * the tail is already in the pipe buffer, so the join is normally instant.
     *
     * @param postExitBound the post-exit bound of the round's handle ({@code
     *     ExecHandle.postExitBound()}, FR19 of fix-terminal-receipt-convergence); never
     *     null, always positive
     * @return the round's events, in wire order; never null, possibly empty
     * @throws StreamDrainTimeoutException if the drain is still reading when the
     *     bound expires — an infrastructure failure of the round, never a silently
     *     partial stream
     * @throws StreamDrainInterruptedException if the round thread is interrupted
     *     before the drain finishes; the bound is blameless on that path, so it is a
     *     separate failure rather than a timeout that would misdirect the operator
     * @throws UncheckedIOException if the drain failed on a genuine I/O error while
     *     the process was alive (D3)
     */
    List<TimestampedEvent> await(Duration postExitBound) {
        Join join = joinWithin(postExitBound);
        if (join != Join.FINISHED) {
            close();
            throw join == Join.INTERRUPTED
                    ? new StreamDrainInterruptedException(bytesRead())
                    : new StreamDrainTimeoutException(postExitBound, bytesRead());
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
     * Ends the drain on any exit path (NFR-R1): the owner's stop, which the stream view's
     * next read observes within a tick — so the parser sees end-of-stream even while a
     * descendant of the process still holds the pipe — and an interrupt for a thread that is
     * inside the parser or its listener (design D5 of make-pipe-reads-non-parking). It closes
     * no pipe and joins nothing: the wait for the stream is {@link #await}'s, bounded by the
     * handle's post-exit bound the round passes, and a second bound of the drain's own here
     * would be a reader's constant beside it (design D17 of fix-terminal-receipt-convergence).
     * Idempotent, and a no-op in effect on the normal path where the drain already ended.
     */
    @Override
    public void close() {
        source.stop();
        thread.interrupt();
    }

    /**
     * The drain thread's body. The try-with-resources closes the view, which is the
     * owner's stop, never a close of the pipe (D5 of make-pipe-reads-non-parking). A
     * failure of the read is recorded rather than thrown: on a {@code roundTimeout}
     * kill the round thread never consults it — it throws its timeout classification
     * first (D3, FR3) — and on any path where it does consult it, the failure is genuine.
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
}
