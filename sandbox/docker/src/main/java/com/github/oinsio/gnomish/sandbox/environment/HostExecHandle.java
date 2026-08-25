package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.Supervision;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

/**
 * The host adapter's {@link ExecHandle}: a thin wrapper over a started local
 * {@link Process} exposing its stdout stream and the wait/kill/wall-time
 * mechanics an agent round and a command check both drive. This is the sole
 * home of the timeout-kill logic that {@code LaunchedAgentProcess} carried
 * before processes moved behind the environment port (FR4).
 *
 * <p>Both waits run through the shared {@link ProcessSupervisor} (design D11 of
 * bound-subprocess-commands), which is what turns the timeout kill into a
 * <em>tree</em> kill: an agent CLI that had spawned subprocesses of its own used
 * to leave them running past the round that launched them, because only the
 * parent was destroyed. The supervisor snapshots the descendants first, asks the
 * whole tree to stop, forces what ignored the request after a short grace, and
 * reaps — so nothing the round started outlives it (FR11, G5).
 *
 * <p>It is also what names an interrupt instead of coding it: a cut-short wait
 * reports {@link Wait.Interrupted} with the flag restored, rather than the
 * {@code -1} a caller could not tell from a process that really exited {@code
 * -1}. The class keeps no interrupt-handling code of its own, which is why its
 * two {@code @DoNotMutate} timing-race exemptions are gone: the one remaining
 * catch lives in the supervisor, driven deterministically by its own spec (M5).
 *
 * <p>Implements FR1, FR4 of add-sandbox-core; FR6, D3, D7 of add-agent-executor;
 * FR6, FR11, G5 of bound-subprocess-commands.
 */
public final class HostExecHandle implements ExecHandle {

    private final Process process;
    private final Instant startedAt;
    private final ProcessSupervisor supervisor = new ProcessSupervisor();

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
        Supervision supervision = supervisor.await(process, timeout);
        return switch (supervision.termination()) {
            case EXITED -> new Wait.Exited(Duration.between(startedAt, clock.now()));
            case TIMED_OUT -> new Wait.TimedOut();
            case INTERRUPTED -> new Wait.Interrupted();
        };
    }

    /**
     * Blocks until the process exits and returns its exit code. Unbounded on
     * purpose — the caller that has one applies it through {@link
     * #waitForExitOrTimeout} — but not unsupervised: an interrupt still kills and
     * reaps the tree instead of leaving an in-box helper's {@code docker exec}
     * running behind a shutdown, and still leaves the flag set for the caller
     * above (FR11).
     */
    @Override
    public int waitForExit() {
        return supervisor.await(process, null).exitCode();
    }
}
