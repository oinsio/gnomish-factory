package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.adapter.command.CommandRun;
import com.github.oinsio.gnomish.adapter.command.CommandRunOutcome;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The verify {@code command} check's use of the shared command-run core ({@link CommandRun}, design
 * D2 of add-command-executor): runs the check's command line through a {@link
 * TaskExecutionEnvironment} — the sole process-launch seam (FR4 of add-sandbox-core) — with a
 * {@code GNOMISH_FINDINGS_FILE} env fragment when a path is supplied (FR8, NFR-S1), bounded by the
 * installation's check timeout (design D12, FR12), and reports a bound that fired in the check's
 * own operator events (NFR-O1). The run mechanics — {@code sh -c}, stderr merged, the process-tree
 * kill on timeout, the bounded tail drained without parking and joined with the handle's post-exit
 * bound — are the core's, shared with the {@code command} stage executor; this class keeps only
 * what is the check's: its findings channel, its timeout, its WARN lines, and the view of the
 * outcome {@link ShellCommandCheckRunner} classifies.
 *
 * <p>Implements FR7, FR8, D6 of add-manual-run; FR4 of add-sandbox-core; FR6, FR12, NFR-O1, D12 of
 * bound-subprocess-commands; FR19 of fix-terminal-receipt-convergence; FR2, FR4 of
 * make-pipe-reads-non-parking; D2 of add-command-executor.
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

    /**
     * The exit code reported when the command never got to choose one. Diagnostic
     * context only: the outcome's {@code termination} already says the command did not
     * run to completion, and the caller branches on that first.
     */
    private static final int UNKNOWN_EXIT_CODE = -1;

    private static final String FINDINGS_FILE_ENV_VAR = "GNOMISH_FINDINGS_FILE";

    private final CommandRun core;

    private final Duration checkTimeout;

    /**
     * A runner over {@code shell} bounded by the documented default timeout, measuring on {@code
     * clock} (FR18 of supervise-daemon-loops-and-embed-dashboard).
     */
    CommandProcessRunner(String shell, InstantSource clock) {
        this(shell, DEFAULT_CHECK_TIMEOUT, clock);
    }

    /**
     * @param shell the shell executable to invoke via {@code -c <command>}
     * @param checkTimeout the hard bound on one check; the composition root passes the
     *     installation's {@code factory.check-command-timeout}, and specs inject a sub-second one
     * @param clock the read-time source for the measured wall time
     */
    CommandProcessRunner(String shell, Duration checkTimeout, InstantSource clock) {
        this(new CommandRun(shell, clock), checkTimeout);
    }

    private CommandProcessRunner(CommandRun core, Duration checkTimeout) {
        this.core = core;
        this.checkTimeout = checkTimeout;
    }

    /**
     * Returns a copy of this runner bounding its checks by {@code checkTimeout} — the seam the
     * composition root threads the installation's configured value through (FR5).
     *
     * @param checkTimeout the hard bound on one check; never null, never negative
     * @return a runner identical but for the bound; never null
     */
    CommandProcessRunner withCheckTimeout(Duration checkTimeout) {
        return new CommandProcessRunner(core, checkTimeout);
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
        return switch (core.run(environment, check.command(), env, checkTimeout)) {
            case CommandRunOutcome.NotStarted ignored -> null;
            case CommandRunOutcome.Exited exited ->
                new CommandOutcome(
                        exited.exitCode(), exited.outputTail(), new ExecHandle.Wait.Exited(exited.wallTime()));
            case CommandRunOutcome.TimedOut timedOut -> {
                log.warn(
                        OperatorEvent.COMMAND_CHECK_TIMED_OUT.head()
                                + "command check timed out and its process tree was killed: check={}, elapsed={}, deadline={}",
                        check.command(),
                        timedOut.elapsed(),
                        checkTimeout);
                yield new CommandOutcome(UNKNOWN_EXIT_CODE, timedOut.outputTail(), new ExecHandle.Wait.TimedOut());
            }
            case CommandRunOutcome.Interrupted interrupted -> {
                log.warn(
                        OperatorEvent.COMMAND_CHECK_INTERRUPTED.head()
                                + "command check interrupted and its process tree was killed: check={}, elapsed={}",
                        check.command(),
                        interrupted.elapsed());
                yield new CommandOutcome(
                        UNKNOWN_EXIT_CODE, interrupted.outputTail(), new ExecHandle.Wait.Interrupted());
            }
        };
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
