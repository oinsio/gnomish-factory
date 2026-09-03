package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code sh -c <command>} through a {@link TaskExecutionEnvironment} — the
 * task environment port is the sole process-launch seam (FR4 of
 * add-sandbox-core) — with a {@code GNOMISH_FINDINGS_FILE} env fragment when a
 * path is supplied (FR8, NFR-S1), stderr merged into stdout, and captures the
 * exit code together with a bounded tail of that one chronological stream
 * (design D6, FR7 of add-manual-run). The environment composes the child
 * environment as the layered allowlist (D6, FR9 of add-sandbox-core) — this
 * class contributes only the factory-set findings-file variable and never
 * touches {@link ProcessBuilder}.
 *
 * <p>The run is bounded by the installation's check timeout (design D12, FR12):
 * a check that has not exited when it expires has its whole process tree killed
 * and reports {@link ExecHandle.Wait.TimedOut} — a quality failure for the
 * caller to classify, carrying the tail captured so far, rather than a run that
 * hangs until an operator notices. The tail is drained concurrently with the
 * command ({@link BoundedTail}), because a stream read to its end on this thread
 * is precisely what a hung command never lets return.
 *
 * <p>Implements FR7, FR8, D6 of add-manual-run; FR4 of add-sandbox-core; FR6,
 * FR12, NFR-O1, D12 of bound-subprocess-commands.
 */
final class CommandProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessRunner.class);

    /**
     * The documented default for a verify command (FR5): generous enough that a
     * real build-and-test check finishes under it, short enough that a wedged one
     * does not hold a take for the rest of the night. An installation changes it
     * through {@code factory.check-command-timeout}.
     */
    static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofMinutes(30);

    /** How long the tail drain is waited on when a straggler still holds the pipe open (D2). */
    static final Duration TAIL_JOIN_BOUND = Duration.ofSeconds(2);

    /**
     * The exit code reported when the command never got to choose one. Diagnostic
     * context only: the outcome's {@code termination} already says the command did not
     * run to completion, and the caller branches on that first.
     */
    private static final int UNKNOWN_EXIT_CODE = -1;

    private static final String FINDINGS_FILE_ENV_VAR = "GNOMISH_FINDINGS_FILE";

    private final String shell;

    private final Duration checkTimeout;

    private final Clock clock;

    /** A runner over {@code shell} bounded by the documented default timeout. */
    CommandProcessRunner(String shell) {
        this(shell, DEFAULT_CHECK_TIMEOUT, new SystemClock());
    }

    /**
     * @param shell the shell executable to invoke via {@code -c <command>}
     * @param checkTimeout the hard bound on one check; the composition root passes the
     *     installation's {@code factory.check-command-timeout}, and specs inject a sub-second one
     * @param clock the read-time source for the measured wall time
     */
    CommandProcessRunner(String shell, Duration checkTimeout, Clock clock) {
        this.shell = shell;
        this.checkTimeout = checkTimeout;
        this.clock = clock;
    }

    /**
     * Returns a copy of this runner bounding its checks by {@code checkTimeout} — the seam the
     * composition root threads the installation's configured value through (FR5).
     *
     * @param checkTimeout the hard bound on one check; never null, never negative
     * @return a runner identical but for the bound; never null
     */
    CommandProcessRunner withCheckTimeout(Duration checkTimeout) {
        return new CommandProcessRunner(shell, checkTimeout, clock);
    }

    /**
     * Runs {@code check}'s command line via {@code sh -c} through {@code
     * environment}, with {@code GNOMISH_FINDINGS_FILE} added when supplied (FR8),
     * merging stdout and stderr into one chronological stream. Returns {@code
     * null} if the process could not even be started (the environment throws
     * {@link ProcessStartException}) instead of propagating, so the caller can
     * turn that into a {@code CannotVerify} verdict without a stack trace
     * crashing the check.
     *
     * <p>Implements FR7, FR8, D6 of add-manual-run; FR4 of add-sandbox-core; FR12
     * of bound-subprocess-commands.
     *
     * @param check the command check to run
     * @param environment the bound task environment the process runs in
     * @param findingsPath the environment-valid path handed to the command as
     *     {@code GNOMISH_FINDINGS_FILE} (allocated under the environment's
     *     scratch root), or {@code null} to run without a findings channel
     * @return the captured exit code, bounded output tail and how the run ended,
     *     or {@code null} if the process failed to start
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, TaskExecutionEnvironment environment, @Nullable String findingsPath) {
        Map<String, String> env = findingsPath == null ? Map.of() : Map.of(FINDINGS_FILE_ENV_VAR, findingsPath);
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(shellCommand(check), env, null, true));
        } catch (ProcessStartException e) {
            return null;
        }

        long startedAt = System.nanoTime();
        BoundedTail tail = BoundedTail.start(handle.output());
        ExecHandle.Wait ended = handle.waitForExitOrTimeout(checkTimeout, clock);
        report(check, ended, Duration.ofNanos(System.nanoTime() - startedAt));
        // Bounded on both paths: the command is over either way, so the drain has only the pipe's
        // remaining bytes to read — unless something that escaped the tree still holds the pipe
        // open, and neither a verdict nor a timeout report may wait on that (design D2).
        String outputTail = tail.join(TAIL_JOIN_BOUND);
        // Only a command that chose its own exit code has one; after a kill the code is the
        // signal's, which would read as an ordinary red check.
        int exitCode = ended instanceof ExecHandle.Wait.Exited ? handle.waitForExit() : UNKNOWN_EXIT_CODE;
        return new CommandOutcome(exitCode, outputTail, ended);
    }

    /**
     * Package-visible overload for callers that do not need the findings-file lifecycle: runs
     * with no {@code GNOMISH_FINDINGS_FILE}.
     */
    @Nullable
    CommandOutcome run(VerifyCheck.Command check, TaskExecutionEnvironment environment) {
        return run(check, environment, null);
    }

    /**
     * Logs the one WARN a bound that fired owes an operator (NFR-O1, NFR-O2):
     * which check ended early, how long it ran, and — for a timeout — the
     * deadline they would raise to give it more. The check's command line is its
     * id: a stage's verify list addresses its command checks by nothing else.
     */
    private void report(VerifyCheck.Command check, ExecHandle.Wait ended, Duration elapsed) {
        switch (ended) {
            case ExecHandle.Wait.Exited ignored -> {
                // A command that answered is silent: a WARN in the log means a bound actually fired.
            }
            case ExecHandle.Wait.TimedOut ignored ->
                log.warn(
                        OperatorEvent.COMMAND_CHECK_TIMED_OUT.head()
                                + "command check timed out and its process tree was killed: check={}, elapsed={}, deadline={}",
                        check.command(),
                        elapsed,
                        checkTimeout);
            case ExecHandle.Wait.Interrupted ignored ->
                log.warn(
                        OperatorEvent.COMMAND_CHECK_INTERRUPTED.head()
                                + "command check interrupted and its process tree was killed: check={}, elapsed={}",
                        check.command(),
                        elapsed);
        }
    }

    private List<String> shellCommand(VerifyCheck.Command check) {
        return List.of(shell, "-c", check.command());
    }

    /**
     * The outcome of one command run: exit code, the bounded output tail, and how
     * the run ended — the caller reads {@code termination} before the exit code, since
     * only a natural exit carries one the command chose.
     *
     * @param exitCode the process's exit code; meaningful on {@link ExecHandle.Wait.Exited}
     * @param outputTail the bounded tail of the merged stdout/stderr stream
     * @param termination whether the command exited, hit the check timeout, or was interrupted
     */
    record CommandOutcome(int exitCode, String outputTail, ExecHandle.Wait termination) {}
}
