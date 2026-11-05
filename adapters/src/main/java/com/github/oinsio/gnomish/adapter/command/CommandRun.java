package com.github.oinsio.gnomish.adapter.command;

import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

/**
 * The shared command-run core (design D2 of add-command-executor): runs {@code <shell> -c
 * <command>} through a {@link TaskExecutionEnvironment} — the sole process-launch seam (FR4 of
 * add-sandbox-core) — with the caller's env fragment and stderr merged into stdout, bounds the wait
 * by the caller's timeout, and reports how the run ended with a bounded tail of that one
 * chronological stream (design D6, FR7 of add-manual-run). A verify {@code command} check and a
 * {@code command} stage both run through it, so the two cannot drift in their timeout, kill or tail
 * semantics; each keeps only its own environment acquisition and its own classification of the
 * {@link CommandRunOutcome}, which this class never makes.
 *
 * <p>The environment composes the child environment as the layered allowlist (D6, FR9 of
 * add-sandbox-core); the core contributes only the caller's fragment and never touches {@link
 * ProcessBuilder}. A command that has not exited when the timeout expires has its whole process tree
 * killed by the handle and is reported {@link CommandRunOutcome.TimedOut} with the tail captured so
 * far (FR12 of bound-subprocess-commands). The tail is drained concurrently with the command ({@link
 * BoundedTail}), because a stream read to its end on this thread is precisely what a hung command
 * never lets return; the drain reads the handle's {@link
 * com.github.oinsio.gnomish.subprocess.ChildOutput} without parking and is joined with the handle's
 * post-exit bound, the core holding no bound of its own (design D17 of
 * fix-terminal-receipt-convergence; D2, D4 of make-pipe-reads-non-parking).
 *
 * <p>The core logs nothing: which bound fired, and in which operator's words, is the caller's
 * report, read from the outcome.
 *
 * <p>Implements FR4, FR7, NFR-O1, NFR-S1, D2 of add-command-executor; FR7, D6 of add-manual-run;
 * FR4 of add-sandbox-core; FR12 of bound-subprocess-commands; FR19 of
 * fix-terminal-receipt-convergence; FR2, FR4 of make-pipe-reads-non-parking.
 */
public final class CommandRun {

    /** The POSIX shell a declared command line is handed to, as {@code sh -c <command>}. */
    public static final String POSIX_SHELL = "sh";

    private final String shell;

    private final InstantSource clock;

    /**
     * @param shell the shell executable invoked as {@code <shell> -c <command>}; {@link #POSIX_SHELL}
     *     in production, a missing binary in a spec that forces a start failure
     * @param clock the read-time source the handle measures the exit instant on
     */
    public CommandRun(String shell, InstantSource clock) {
        this.shell = shell;
        this.clock = clock;
    }

    /**
     * Runs {@code command} via {@code <shell> -c} through {@code environment} with {@code env}
     * added to the child environment, merging stdout and stderr, and waits at most {@code timeout}.
     * A process that cannot be started is reported, never thrown.
     *
     * @param environment the bound task environment the process runs in; never null
     * @param command the command line, handed to the shell verbatim; never null
     * @param env the variables the caller adds to the environment's allowlisted child environment;
     *     never null, empty for none
     * @param timeout the hard bound on the run; never null, never negative
     * @return how the run ended, with the bounded tail of its merged output; never null
     */
    public CommandRunOutcome run(
            TaskExecutionEnvironment environment, String command, Map<String, String> env, Duration timeout) {
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(List.of(shell, "-c", command), env, null, StderrPolicy.MERGED));
        } catch (ProcessStartException e) {
            return new CommandRunOutcome.NotStarted(e);
        }

        BoundedTail tail = BoundedTail.start(handle.output());
        ExecHandle.Wait ended = handle.waitForExitOrTimeout(timeout, clock);
        // One source of time for every ending: the handle's start read on the caller's clock, as the
        // handle measures an exited run's wall time, so a killed run's span is on the same clock.
        Duration elapsed = Duration.between(handle.startedAt(), clock.instant());
        // Bounded on every path, by the handle's own post-exit bound (design D17 of
        // fix-terminal-receipt-convergence): the command is over either way, so the drain reads
        // what is pending and ends as soon as it sees the exit, even with the pipe still held
        // (design D2, D4 of make-pipe-reads-non-parking). Only a holder that keeps writing keeps it
        // open to the bound, and no outcome may wait past that.
        String outputTail = tail.join(handle);
        return switch (ended) {
            // Only a command that chose its own exit code has one; after a kill the code is the
            // signal's, which a caller would read as an ordinary failure.
            case ExecHandle.Wait.Exited exited ->
                new CommandRunOutcome.Exited(handle.waitForExit(), outputTail, exited.wallTime());
            case ExecHandle.Wait.TimedOut ignored -> new CommandRunOutcome.TimedOut(outputTail, elapsed);
            case ExecHandle.Wait.Interrupted ignored -> new CommandRunOutcome.Interrupted(outputTail, elapsed);
        };
    }
}
