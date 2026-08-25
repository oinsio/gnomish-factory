package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

/**
 * A live handle to a process started by {@link
 * TaskExecutionEnvironment#exec(ExecCommand)} (design D1): its streamed output,
 * the instant it started, and the ways a caller waits for it to finish. The
 * handle is host-agnostic — it exposes streams and exit control, never a raw OS
 * {@code Process} or a filesystem path — so an agent round parsing stream-json
 * and a command check reading a bounded tail drive the same contract whether
 * the process runs on the host or inside a container.
 *
 * <p>Implements FR1, FR4 of add-sandbox-core.
 */
public interface ExecHandle {

    /**
     * The process's standard output as a stream — the merged stdout/stderr
     * stream when {@link ExecCommand#mergeStderr()} was set, stdout alone
     * otherwise. Everything read here is inert data (NFR-S3).
     *
     * @return the output stream; never null
     */
    InputStream output();

    /**
     * The instant the process was started, read immediately after the start
     * seam returned — the anchor for wall-time telemetry (FR6 of
     * add-agent-executor).
     *
     * @return the start instant; never null
     */
    Instant startedAt();

    /**
     * Waits up to {@code timeout} for the process to exit; on expiry the process
     * and every descendant it spawned are killed and reaped before returning
     * {@link Wait.TimedOut} (an infrastructure failure of the round — no verdict
     * exists), otherwise returns {@link Wait.Exited} with the measured
     * start-to-exit wall time. A wait cut short by an interrupt kills the same
     * tree and returns {@link Wait.Interrupted}, with the interrupt flag
     * restored (FR11 of bound-subprocess-commands).
     *
     * @param timeout the round timeout budget; never null, never negative
     * @param clock the read-time source for the exit instant; never null
     * @return the wait outcome; never null
     */
    Wait waitForExitOrTimeout(Duration timeout, Clock clock);

    /**
     * Blocks until the process exits and returns its exit code — the form a
     * command check uses after reading its output stream to completion.
     *
     * @return the process exit code
     */
    int waitForExit();

    /**
     * The outcome of {@link #waitForExitOrTimeout}: a natural exit within budget
     * ({@link Exited}), a timeout that forced a kill ({@link TimedOut}), or a
     * wait cut short by an interrupt ({@link Interrupted}).
     *
     * <p>The three are named rather than folded into an exit code precisely
     * because they mean different things to the caller deciding what to report:
     * only {@link Exited} carries a verdict at all, and an interrupt is the one
     * case where nothing about the work is known and nothing should be blamed on
     * the round's own budget (FR11 of bound-subprocess-commands).
     */
    sealed interface Wait {

        /**
         * The process exited on its own within the timeout budget.
         *
         * @param wallTime the measured start-to-exit span; never null
         */
        record Exited(Duration wallTime) implements Wait {}

        /** The timeout expired before exit; the process tree was killed and reaped. */
        record TimedOut() implements Wait {}

        /**
         * The waiting thread was interrupted before the process exited; the
         * process tree was killed and reaped and the interrupt flag restored.
         * Distinct from {@link TimedOut}: the round's budget is blameless, so an
         * operator must not be told the work took too long.
         */
        record Interrupted() implements Wait {}
    }
}
