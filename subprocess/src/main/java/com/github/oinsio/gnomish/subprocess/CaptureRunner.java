package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * {@link ProcessSupervisor} wrapped for the capture-shaped callers — the ones that start a command,
 * take its stdout and stderr as separate strings, and are done ({@code GitProcessRunner},
 * {@code DockerCli.run}). Streaming callers use the supervisor directly and keep their own readers.
 *
 * <p>The drains start immediately after the process does and are joined once the wait has resolved,
 * on every termination with one post-exit bound the supervisor derives from its kill grace (design
 * D15 of fix-terminal-receipt-convergence): a descendant holding the pipe after a clean exit must
 * not delay the result any more than a straggler on the kill path (design D2). The drains read the
 * child's {@link ChildOutput}, which never parks: a holder that writes nothing lets a drain end on
 * its own as soon as it sees the exit, the capture complete (D2 of make-pipe-reads-non-parking).
 * When the bound expires with a drain still open — a holder that keeps writing — the drain is
 * stopped, no pipe is closed (D3, D4), and the capture is flagged as {@link Captured#truncated()}. A cut
 * clean exit with code zero is refused with {@link CutCaptureException}, the one refusal of a cut
 * capture (design D15 as amended 2026-10-17); a failed, timed-out or interrupted invocation keeps
 * its partial output, the cut named at the end of its stderr.
 *
 * <p>Policy stays with the caller (NG4). The runner launches the {@link ProcessBuilder} the caller
 * built — environment, working directory and redirects included — and hands back what it captured;
 * credential scrubbing, output caps, and what a given exit code means are none of its business.
 *
 * <p>The command is started through the {@link ProcessLaunch} the runner holds, never by the
 * builder itself (design D16 of fix-terminal-receipt-convergence), so a spec drives the drain path
 * on a fake process instead of on a script whose timing it cannot control.
 *
 * <p>Once the drains are joined the command's process group is swept ({@link ProcessGroupSweep},
 * design D19), on every termination and before a cut is refused, so a descendant the child lost to
 * reparenting dies with the invocation; what the sweep found rides {@link Captured#sweep()}, and
 * the result is otherwise unchanged by it. A launch that threw has no process, and nothing is swept.
 *
 * <p>Implements FR2, FR3, FR6, FR10 of bound-subprocess-commands; FR16, FR17, FR18, FR21, FR23,
 * NFR-P1 of fix-terminal-receipt-convergence; FR4 of make-pipe-reads-non-parking.
 *
 * @param supervisor the wait/kill discipline to run the command under, and the source of the
 *     post-exit bound its drains are joined with
 * @param launch the one way the command is started
 * @param sweep the post-exit kill of the command's process group
 */
public record CaptureRunner(ProcessSupervisor supervisor, ProcessLaunch launch, ProcessGroupSweep sweep) {

    /**
     * A runner over a default {@link ProcessSupervisor} and the module's one production launch,
     * {@link ProcessLaunch#grouped()} — a Local Default of this module, since no second production
     * form exists (design D16 as amended).
     */
    public CaptureRunner() {
        this(new ProcessSupervisor(), ProcessLaunch.grouped());
    }

    /**
     * A runner over {@code supervisor} and {@code launch}, sweeping with the host's own {@code
     * kill} — the Local Default of this module's one sweep.
     *
     * @param supervisor the wait/kill discipline, and the source of the post-exit bound
     * @param launch the one way the command is started
     */
    public CaptureRunner(ProcessSupervisor supervisor, ProcessLaunch launch) {
        this(supervisor, launch, new ProcessGroupSweep());
    }

    /**
     * Starts {@code builder}, drains both its streams concurrently, and waits under {@code
     * deadline}. The command is given no input: its standard input is {@code /dev/null} (design D20
     * of fix-terminal-receipt-convergence), so a command that falls back to reading it — {@code git
     * commit-tree} with an empty message — sees end-of-input at once instead of waiting on a pipe
     * nobody writes to (found by the final gate of tighten-spec-contract, task 9.17).
     *
     * @param builder the fully configured command; started by this method, through the launch
     * @param deadline the hard bound on the wait, or {@code null} for an unbounded one
     * @return the named termination, the exit value, and the captured streams
     * @throws IOException if the binary could not be launched — deliberately the JDK's own
     *     exception, since what an unlaunchable binary means is the caller's classification; also
     *     when the launcher started and could not exec it ({@link ProcessLaunch#launcherCouldNotExec})
     * @throws CutCaptureException if the command exited with code zero while a drain was still open
     *     at the post-exit bound — the capture, carried inside, may be a prefix
     */
    public Captured run(ProcessBuilder builder, @Nullable Duration deadline) throws IOException {
        return run(builder, deadline, StdinSource.NONE);
    }

    /**
     * {@link #run(ProcessBuilder, Duration)} for a command that reads its whole input from
     * standard input — a {@code git update-ref --stdin} transaction. The launch hands the command
     * {@code input} as a file (design D20): no thread of the factory writes into a pipe, so a
     * command that exits before reading everything, or a descendant that keeps its stdin, cannot
     * stall the caller; the command is judged by the exit the wait observes.
     *
     * @param builder the fully configured command; started by this method, through the launch
     * @param deadline the hard bound on the wait, or {@code null} for an unbounded one
     * @param input the bytes the command reads from standard input, in full
     * @return the named termination, the exit value, and the captured streams
     * @throws IOException if the binary could not be launched, or its input not written
     * @throws CutCaptureException if the command exited with code zero while a drain was cut
     */
    public Captured run(ProcessBuilder builder, @Nullable Duration deadline, byte[] input) throws IOException {
        return run(builder, deadline, StdinSource.of(input));
    }

    private Captured run(ProcessBuilder builder, @Nullable Duration deadline, StdinSource stdin) throws IOException {
        Process process = launch.start(builder, stdin);
        Drain stdout = Drain.start(ChildOutput.stdout(process), "subprocess-stdout");
        Drain stderr = Drain.start(ChildOutput.stderr(process), "subprocess-stderr");
        Captured captured = await(process, stdout, stderr, deadline);
        if (ProcessLaunch.launcherCouldNotExec(captured.termination(), captured.exitCode(), captured.stderr())) {
            // The launcher started and could not exec the program (design D19 as amended): the
            // launch failure the caller classifies, as start() reported it before a launcher existed.
            // The message names the binary the caller configured, never the captured text.
            throw new IOException("Cannot run program \"" + builder.command().getFirst()
                    + "\": the process-group launcher could not execute it (exit " + captured.exitCode() + ")");
        }
        return captured;
    }

    /**
     * Waits, joins the drains with the post-exit bound, and judges a cut with Go's precedence
     * (design D15 as amended 2026-10-17): a clean exit with code zero whose drain was cut is refused
     * with {@link CutCaptureException}, since its stdout may be a prefix a reader would take for the
     * whole value; on every other termination the invocation's own failure stands and the cut is
     * named at the end of its stderr, so the partial output stays readable as diagnostics.
     */
    private Captured await(Process process, Drain stdout, Drain stderr, @Nullable Duration deadline) {
        Supervision supervision = supervisor.await(process, deadline);
        Duration bound = supervisor.postExitBound();
        boolean truncated = Drain.joinAll(bound, stdout, stderr);
        GroupSweep swept = sweep.sweep(process);
        // An interrupt that lands after a clean exit cuts the drain joins short, so the capture may
        // be a prefix — reported as INTERRUPTED rather than dressed up as the complete output of a
        // finished command (FR6). Conservative on purpose: an interrupt in the instant after the
        // joins completed is classified the same way, which at worst re-runs work that did
        // complete — the same trade CapturedExec documents.
        Termination termination = supervision.termination() == Termination.EXITED
                        && Thread.currentThread().isInterrupted()
                ? Termination.INTERRUPTED
                : supervision.termination();
        int exitCode = supervision.exitCode();
        if (!truncated) {
            return new Captured(termination, exitCode, stdout.captured(), stderr.captured(), false, swept);
        }
        CutCaptureException.CutStreams streams = CutCaptureException.CutStreams.of(stdout, stderr);
        if (CutCaptureException.refuses(termination, exitCode, truncated)) {
            Captured cut = new Captured(termination, exitCode, stdout.captured(), stderr.captured(), true, swept);
            throw new CutCaptureException(cut, streams, bound);
        }
        String named = CutCaptureException.withNotice(stderr.captured(), CutCaptureException.notice(streams, bound));
        return new Captured(termination, exitCode, stdout.captured(), named, true, swept);
    }
}
