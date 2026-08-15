package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The host adapter's {@link ExecHandle}: a thin wrapper over a started local
 * {@link Process} exposing its stdout stream and the wait/kill/wall-time
 * mechanics an agent round and a command check both drive. This is the sole
 * home of the timeout-kill logic that {@code LaunchedAgentProcess} carried
 * before processes moved behind the environment port (FR4).
 *
 * <p>Implements FR1, FR4 of add-sandbox-core; FR6, D3, D7 of add-agent-executor.
 */
public final class HostExecHandle implements ExecHandle {

    private final Process process;
    private final Instant startedAt;

    /**
     * @param process the started subprocess; never null
     * @param startedAt the instant it was started, read immediately after start; never null
     */
    public HostExecHandle(Process process, Instant startedAt) {
        this.process = process;
        this.startedAt = startedAt;
    }

    @Override
    public InputStream output() {
        return process.getInputStream();
    }

    @Override
    public Instant startedAt() {
        return startedAt;
    }

    @Override
    public Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
        boolean exitedInTime = waitForAtMost(timeout);
        if (!exitedInTime) {
            kill();
            return new Wait.TimedOut();
        }
        return new Wait.Exited(Duration.between(startedAt, clock.now()));
    }

    @Override
    public int waitForExit() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            return interrupted();
        }
    }

    /**
     * PIT M4 documented exception (build.gradle has the full rationale): the
     * {@code catch} is a genuine timing race — {@link Process#waitFor(long,
     * TimeUnit)} blocks only for the brief remaining window until the
     * already-running subprocess exits or the timeout expires, and forcing a
     * thread interrupt to land inside that window is not reliably reproducible
     * in a unit test. Both outcomes (in-time exit, timeout expiry) are covered
     * by HostExecHandle timeout specs.
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
     * Process#destroyForcibly()}'s contract, blocking on its exit afterwards is
     * the documented way to avoid leaking the OS process, and returns quickly
     * since the forced destroy is already in flight.
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
     * {@code catch} is a genuine timing race, not reliably reproducible in a
     * unit test — same rationale as {@link #waitForAtMost}. The happy path is
     * covered by every {@code waitForExit} spec.
     */
    @DoNotMutate
    private int interrupted() {
        Thread.currentThread().interrupt();
        return -1;
    }
}
