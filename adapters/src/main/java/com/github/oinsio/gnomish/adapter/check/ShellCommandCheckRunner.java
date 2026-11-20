package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.adapter.command.CommandRun;
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.time.InstantSource;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real command check runner (design D6): runs {@code check} through a {@link
 * CommandProcessRunner} — the check's use of the shared command-run core {@link CommandRun}
 * (design D2 of add-command-executor) — over the environment acquired from the run's {@link
 * CheckEnvironmentSource} (host workspace environment by default; the round's leased box or a fresh
 * box in sandboxed mode) — the task environment port is the sole process-launch seam (FR4 of
 * add-sandbox-core). {@link #probe} judges the run once, as a {@link ShellOutcome} — the exit code
 * is status, never a verdict (FR5, design D5 as amended of migrate-verify-to-executor-contract) —
 * and the {@link ShellExecutor}, its one consumer, renders it as the executor contract's result,
 * which the one contract → domain mapping carries to the engine (D9). The runner constructs no
 * verdict: the engine's {@code VerdictDerivation} derives it (D5, D9).
 *
 * <p>The findings channel follows FR1/NFR-S3 of add-sandbox-core: the path is allocated in the
 * environment's scratch area (outside the working copy, inside the environment boundary), handed
 * to the command as {@code GNOMISH_FINDINGS_FILE}, and read back through the environment's
 * size-capped {@code readFile} by the executor contract's one result reader (FR6 of
 * define-executor-contract); {@code dispose()} removes the scratch area whatever the outcome. The
 * child environment is the layered allowlist carried by {@link #childEnv} (D6, FR9); {@link
 * #withChildEnv} threads the run's allowlist — passthrough plus the active tracker adapter's
 * declared credential names — per run.
 *
 * <p>Both infrastructure exits that happen before any exit code exists — the environment the
 * check needs cannot be served, and the process refuses to start — carry a WARN naming the check
 * (FR5 of harden-logging-observability): from the escalation report alone they look exactly like an
 * endpoint that was merely slow.
 *
 * <p>Implements FR7, FR8, NFR-R2, NFR-S1, D6 of add-manual-run; FR1, FR4, FR9, NFR-S3 of
 * add-sandbox-core; FR5 of harden-logging-observability; FR6 of define-executor-contract; FR5, FR7
 * of migrate-verify-to-executor-contract.
 */
public record ShellCommandCheckRunner(
        CommandProcessRunner processRunner,
        InstantSource clock,
        ChildEnvAllowlist childEnv,
        CheckEnvironmentSource environments) {

    private static final Logger log = LoggerFactory.getLogger(ShellCommandCheckRunner.class);

    /**
     * How much of a check's command stands in for its identity in a log line. A {@code command}
     * check is identified by nothing but its command text, which a manifest may write as a
     * multi-line block — so the identity goes through {@link LogText} like any other text that
     * did not come from this process.
     */
    private static final int IDENTITY_CAP_CHARS = 200;

    /**
     * The {@code sh}-backed runner with an empty child-environment allowlist.
     *
     * <p>Implements FR18 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @param clock the time source every check's measured wall time is read from — the
     *     composition root's one; never null
     */
    public ShellCommandCheckRunner(InstantSource clock) {
        this(new CommandProcessRunner(CommandRun.POSIX_SHELL, clock), clock, ChildEnvAllowlist.none());
    }

    private ShellCommandCheckRunner(
            CommandProcessRunner processRunner, InstantSource clock, ChildEnvAllowlist childEnv) {
        this(processRunner, clock, childEnv, new HostCheckEnvironmentSource(clock, childEnv));
    }

    /**
     * Package-visible constructor for tests that need to force a process start failure (e.g. a
     * nonexistent shell executable).
     *
     * @param shell the shell executable to invoke via {@code -c <command>}
     * @param clock the time source the measured wall time is read from; never null
     */
    ShellCommandCheckRunner(String shell, InstantSource clock) {
        this(new CommandProcessRunner(shell, clock), clock, ChildEnvAllowlist.none());
    }

    /**
     * Returns a copy of this runner whose check processes compose their child environment from
     * {@code childEnv} — the run's layered allowlist (D6, FR9 of add-sandbox-core), carrying the
     * operator passthrough and the active tracker adapter's declared credential names, so a
     * tracker credential can never reach a command check by construction (FR11, NFR-S1, D11 of
     * add-claim-heartbeat). {@code RunAssembly} threads the same allowlist the agent
     * adapters use through this seam per run. The host environment source is rebuilt around the
     * new allowlist; a sandboxed source applied later ({@link #withEnvironments}) wins.
     *
     * @param childEnv the layered child-environment allowlist; never null
     * @return a runner identical but for the allowlist; never null
     */
    public ShellCommandCheckRunner withChildEnv(ChildEnvAllowlist childEnv) {
        return new ShellCommandCheckRunner(processRunner, clock, childEnv);
    }

    /**
     * Returns a copy of this runner bounding every {@code command} check by {@code checkTimeout} —
     * the installation's {@code factory.check-command-timeout} (FR5, FR12 of
     * bound-subprocess-commands). A check that has not exited when it expires is killed tree-wide
     * and fails as a quality failure carrying the tail captured so far, rather than hanging the
     * run.
     *
     * @param checkTimeout the hard bound on one check; never null, never negative
     * @return a runner identical but for the bound; never null
     */
    public ShellCommandCheckRunner withCheckTimeout(Duration checkTimeout) {
        return new ShellCommandCheckRunner(processRunner.withCheckTimeout(checkTimeout), clock, childEnv, environments);
    }

    /**
     * Returns a copy of this runner acquiring check environments from {@code environments} — the
     * sandboxed source serving same-box checks from the round lease and fresh-box checks from the
     * attempt commit (FR13, the integration pass of add-sandbox-core). Apply after {@link
     * #withChildEnv}: that rebind resets the source to the host default.
     *
     * @param environments the check environment source; never null
     * @return a runner identical but for the environment source; never null
     */
    public ShellCommandCheckRunner withEnvironments(CheckEnvironmentSource environments) {
        return new ShellCommandCheckRunner(processRunner, clock, childEnv, environments);
    }

    /**
     * Runs {@code check}'s command in the environment its source serves for {@code workspace}, and
     * judges the run once (FR5 of migrate-verify-to-executor-contract).
     *
     * @param check the command check; never null
     * @param workspace the engine's workspace for the round under verification; never null
     * @return what the run found; never null
     */
    ShellOutcome probe(VerifyCheck.Executor check, Workspace workspace) {
        CheckEnvironmentSource.Acquired acquired;
        try {
            acquired = environments.acquire(check, workspace);
        } catch (CheckEnvironmentUnavailableException e) {
            log.warn(
                    OperatorEvent.COMMAND_CHECK_NO_ENVIRONMENT.head()
                            + "command check '{}' cannot be verified: no environment to run it in",
                    identityOf(check),
                    e);
            return new ShellOutcome.Unanswerable(
                    UntrustedText.subprocess(e.getMessage() != null ? e.getMessage() : e.toString()),
                    NoCheckDetails.NO_DETAILS);
        }
        try (acquired) {
            TaskExecutionEnvironment environment = acquired.environment();
            String findingsPath = environment.scratchRoot() + "/findings-" + UUID.randomUUID() + ".json";
            CommandProcessRunner.CommandOutcome outcome = processRunner.run(check, environment, findingsPath);
            if (outcome == null) {
                // throwable-not-subject: the runner reports a failed start as a null outcome, so
                //     there is no throwable to attach here — the cause was logged where it was
                //     caught, and this line is the decision that turns it into an infrastructure failure.
                log.warn(
                        OperatorEvent.COMMAND_CHECK_PROCESS_START_FAILED.head()
                                + "command check '{}' cannot be verified: the process failed to start",
                        identityOf(check));
                return new ShellOutcome.Unanswerable(
                        UntrustedText.manifest("failed to start command: " + check.checkId()),
                        NoCheckDetails.NO_DETAILS);
            }
            return ShellOutcome.judge(identityOf(check), outcome, environment, findingsPath);
        }
    }

    /** The check's log-plane identity: its command, flattened and capped so one check is one line. */
    private static String identityOf(VerifyCheck.Executor check) {
        return LogText.forLog(check.checkId(), IDENTITY_CAP_CHARS);
    }
}
