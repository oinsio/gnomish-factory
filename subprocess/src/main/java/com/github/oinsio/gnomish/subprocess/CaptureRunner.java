package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * {@link ProcessSupervisor} wrapped for the capture-shaped callers — the ones that start a command,
 * take its stdout and stderr as separate strings, and are done ({@code GitProcessRunner},
 * {@code DockerCli.run}). Streaming callers use the supervisor directly and keep their own readers.
 *
 * <p>The drains start immediately after the process does and are joined once the wait has resolved:
 * unbounded on a normal exit, where waiting is what makes the capture complete, and bounded on the
 * kill path, where a straggler holding the pipe must not delay the result (design D2).
 *
 * <p>Policy stays with the caller (NG4). The runner launches the {@link ProcessBuilder} the caller
 * built — environment, working directory and redirects included — and hands back what it captured;
 * credential scrubbing, output caps, and what a given exit code means are none of its business.
 *
 * <p>Implements FR2, FR3, FR6, FR10 of bound-subprocess-commands.
 *
 * @param supervisor the wait/kill discipline to run the command under
 * @param drainJoinBound how long a kill-path drain join may take before the result is returned
 *     with whatever was captured
 */
public record CaptureRunner(ProcessSupervisor supervisor, Duration drainJoinBound) {

    /** How long the kill path waits for a drain whose pipe someone else is still holding open. */
    static final Duration DEFAULT_DRAIN_JOIN_BOUND = Duration.ofSeconds(2);

    /** A runner over a default {@link ProcessSupervisor}, with the default drain-join bound. */
    public CaptureRunner() {
        this(new ProcessSupervisor(), DEFAULT_DRAIN_JOIN_BOUND);
    }

    /**
     * Starts {@code builder}, drains both its streams concurrently, and waits under {@code
     * deadline}.
     *
     * @param builder the fully configured command; started by this method
     * @param deadline the hard bound on the wait, or {@code null} for an unbounded one
     * @return the named termination, the exit value, and the captured streams
     * @throws IOException if the binary could not be launched — deliberately the JDK's own
     *     exception, since what an unlaunchable binary means is the caller's classification
     */
    public Captured run(ProcessBuilder builder, @Nullable Duration deadline) throws IOException {
        Process process = builder.start();
        Drain stdout = Drain.start(process.getInputStream(), "subprocess-stdout");
        Drain stderr = Drain.start(process.getErrorStream(), "subprocess-stderr");
        Supervision supervision = supervisor.await(process, deadline);
        Duration joinBound = supervision.termination() == Termination.EXITED ? null : drainJoinBound;
        String out = stdout.join(joinBound);
        String err = stderr.join(joinBound);
        // An interrupt that lands after a clean exit cuts the unbounded drain joins short, so the
        // capture may be a prefix — reported as INTERRUPTED rather than dressed up as the complete
        // output of a finished command (FR6). Conservative on purpose: an interrupt in the instant
        // after the joins completed is classified the same way, which at worst re-runs work that
        // did complete — the same trade CapturedExec documents.
        Termination termination = supervision.termination() == Termination.EXITED
                        && Thread.currentThread().isInterrupted()
                ? Termination.INTERRUPTED
                : supervision.termination();
        return new Captured(termination, supervision.exitCode(), out, err);
    }
}
