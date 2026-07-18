package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The result of {@link AgentProcessLauncher#launch}: the started {@link
 * Process} — whose {@code getInputStream()} a caller pipes into {@link
 * StreamJsonParser} — paired with the resolved command line for logging and
 * diagnostics (NFR-O1) and the {@link Instant} the process was started at,
 * read immediately after {@code builder.start()} returns (design D3). {@link
 * #waitForExitMeasuringWallTime} is this record's one behaviour: it awaits
 * the process's exit and returns the process start → exit span as a {@link
 * Duration}, independent of whether the caller ever parses the process's
 * stdout at all (FR6) — a caller that pipes stdout into {@link
 * StreamJsonParser} first and then calls this method still measures the same
 * start-to-exit span, since parsing runs to stream exhaustion before the
 * process typically exits.
 *
 * <p>Implements FR6, D3 of add-agent-executor.
 *
 * @param process the started subprocess; never null
 * @param command the exact argv the process was started with, in order;
 *     never null
 * @param startedAt the instant the process was started; never null
 */
public record LaunchedAgentProcess(Process process, List<String> command, Instant startedAt) {

    public LaunchedAgentProcess {
        requireNonNull(process, "process");
        requireNonNull(command, "command");
        requireNonNull(startedAt, "startedAt");
    }

    /**
     * Blocks until {@link #process} exits, then returns the {@link Duration}
     * between {@link #startedAt} and the {@code clock}'s reading taken the
     * moment {@link Process#waitFor()} returns — process start → exit,
     * measured independently of stream-json parsing (FR6, D3). Precision is
     * telemetry-grade, not billing-grade (NFR-O3): this method measures wall
     * time only, never tool-call durations, which {@link ToolTraceBuilder}
     * derives separately and may sum to more than this value when tool calls
     * run in parallel.
     *
     * @param clock the read-time source for the exit instant; never null
     *     (production wiring uses {@link
     *     com.github.oinsio.gnomish.adapter.engine.SystemClock}, tests a
     *     controllable fake)
     * @return the measured wall time; never null, never negative
     */
    public Duration waitForExitMeasuringWallTime(Clock clock) {
        waitFor();
        return Duration.between(startedAt, clock.now());
    }

    /**
     * Waits up to {@code timeout} for {@link #process} to exit naturally. A
     * hung CLI must not hang the engine (design D7): on timeout expiry the
     * process is forcibly killed ({@link Process#destroyForcibly()}) and
     * reaped before this method returns, and {@link RoundWait.TimedOut} is
     * returned — an infrastructure failure of the round, no verdict exists
     * (FR13, NFR-R1). On natural exit within the budget the process is left
     * untouched and {@link RoundWait.Exited} carries the same start → exit
     * wall time as {@link #waitForExitMeasuringWallTime} (FR6, D3).
     *
     * @param timeout the round's configured {@code roundTimeout} budget;
     *     never null, never negative
     * @param clock the read-time source for the exit instant on the natural
     *     path; never null
     * @return {@link RoundWait.Exited} with the measured wall time, or {@link
     *     RoundWait.TimedOut} if {@code timeout} expired first; never null
     */
    public RoundWait waitForExitOrTimeout(Duration timeout, Clock clock) {
        boolean exitedInTime = waitForAtMost(timeout);
        if (!exitedInTime) {
            kill();
            return new RoundWait.TimedOut();
        }
        return new RoundWait.Exited(Duration.between(startedAt, clock.now()));
    }

    /**
     * The outcome of {@link #waitForExitOrTimeout}: either the process
     * exited on its own within budget ({@link Exited}), or {@code
     * roundTimeout} expired first and the process was killed ({@link
     * TimedOut}) — an infrastructure failure, no verdict exists (FR13,
     * NFR-R1, D7).
     */
    public sealed interface RoundWait {

        /**
         * The process exited naturally within the timeout budget.
         *
         * @param wallTime the measured start → exit span; never null, never
         *     negative
         */
        record Exited(Duration wallTime) implements RoundWait {}

        /**
         * {@code roundTimeout} expired before the process exited; it was
         * forcibly killed. No verdict exists for this round (FR13, NFR-R1).
         */
        record TimedOut() implements RoundWait {}
    }

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): the
     * {@code catch} block is a genuine timing race — {@link
     * Process#waitFor(long, TimeUnit)} blocks only for the brief remaining
     * window until the already-running subprocess exits or the timeout
     * expires, and forcing a thread interrupt to land inside that window is
     * not reliably reproducible in a unit test (mirrors {@link #waitFor()}'s
     * identical rationale). Both outcomes (in-time exit, timeout expiry) are
     * covered by {@code waitForExitOrTimeout} specs in
     * LaunchedAgentProcessTimeoutSpec.
     */
    @DoNotMutate
    private boolean waitForAtMost(Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Forcibly kills a timed-out process and reaps it: per {@link
     * Process#destroyForcibly()}'s contract this returns the same {@link
     * Process}, and blocking on its exit afterwards is the documented way to
     * avoid leaking the OS process (zombie reaping) — this second wait
     * returns quickly since the forced destroy is already in flight.
     */
    @DoNotMutate
    private void kill() {
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): the
     * {@code catch} block is a genuine timing race — {@link Process#waitFor()}
     * blocks only for the brief remaining window until the already-running
     * subprocess exits, and forcing a thread interrupt to land inside that
     * window is not reliably reproducible in a unit test (mirrors {@code
     * CommandProcessRunner#waitFor}'s identical rationale). The happy path is
     * covered by every {@code waitForExitMeasuringWallTime} spec in
     * LaunchedAgentProcessSpec.
     */
    @DoNotMutate
    private void waitFor() {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fails fast on a null {@code process}/{@code command}/{@code startedAt}:
     * the launcher never omits any of them. Kept as an explicit static method
     * rather than inline in the compact constructor: PIT's record filter
     * suppresses all mutations inside a record's canonical constructor, which
     * would silently exempt this validation from the 100% mutation gate.
     */
    private static void requireNonNull(Object value, String component) {
        if (value == null) {
            throw new NullPointerException("LaunchedAgentProcess." + component + " must not be null");
        }
    }
}
