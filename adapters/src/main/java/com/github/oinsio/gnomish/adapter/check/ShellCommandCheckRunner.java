package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The real command check runner (design D6): runs {@code check} through a {@link
 * CommandProcessRunner} over the environment acquired from the run's {@link CheckEnvironmentSource}
 * (host workspace environment by default; the round's leased box or a fresh box in sandboxed mode)
 * — the task environment
 * port is the sole process-launch seam (FR4 of add-sandbox-core) — then classifies the exit code
 * per the engine's table (FR7, D6): exit 0 is {@link Verdict.Pass} — any findings content is
 * ignored with a logged warning (FR8); exit 126/127 (shell convention for "not executable" /
 * "not found") is {@link Verdict.CannotVerify} — an infrastructure failure, honoring the same
 * classification a missing binary would get, and the findings channel plays no role; any other
 * non-zero exit is {@link Verdict.Fail} carrying either the findings {@link FindingsFileReader}
 * parsed from the channel (if present and well-formed) or one synthetic {@link Finding} built from
 * the output tail (if the content is absent, empty, or malformed — NFR-R2: the exit-code verdict
 * always stands). A process start failure (a null {@link CommandProcessRunner#run} result) is also
 * {@link Verdict.CannotVerify}.
 *
 * <p>The findings channel follows FR1/NFR-S3 of add-sandbox-core: the path is allocated in the
 * environment's scratch area (outside the working copy, inside the environment boundary), handed
 * to the command as {@code GNOMISH_FINDINGS_FILE}, and — only for a run that chose its own exit
 * code — read back through the environment's size-capped {@code readFile} — bytes in memory, never
 * a factory-side file; {@code dispose()} removes the scratch area whatever the outcome. The child environment is the layered allowlist
 * carried by {@link #childEnv} (D6, FR9); {@link #withChildEnv} threads the run's allowlist —
 * passthrough plus the active tracker adapter's declared credential names — per run.
 *
 * <p>Implements FR7, FR8, NFR-R2, NFR-S1, D6 of add-manual-run; FR1, FR4, FR9, NFR-S3 of
 * add-sandbox-core.
 */
public record ShellCommandCheckRunner(
        CommandProcessRunner processRunner,
        Clock clock,
        ChildEnvAllowlist childEnv,
        CheckEnvironmentSource environments)
        implements CommandCheckRunner {

    /**
     * The read cap applied when the findings channel is read back through the environment
     * (NFR-S3, NFR-C1 of add-sandbox-core): far above any sane findings report, far below
     * resource abuse; truncated JSON parses as malformed and degrades to the synthetic finding.
     */
    static final long FINDINGS_READ_CAP_BYTES = 256 * 1024;

    public ShellCommandCheckRunner() {
        this(new CommandProcessRunner("sh"), new SystemClock(), ChildEnvAllowlist.none());
    }

    private ShellCommandCheckRunner(CommandProcessRunner processRunner, Clock clock, ChildEnvAllowlist childEnv) {
        this(processRunner, clock, childEnv, new HostCheckEnvironmentSource(clock, childEnv));
    }

    /**
     * Package-visible constructor for tests that need to force a process start failure (e.g. a
     * nonexistent shell executable).
     *
     * @param shell the shell executable to invoke via {@code -c <command>}
     */
    ShellCommandCheckRunner(String shell) {
        this(new CommandProcessRunner(shell), new SystemClock(), ChildEnvAllowlist.none());
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

    @Override
    public Verdict run(VerifyCheck.Command check, Workspace workspace) {
        CheckEnvironmentSource.Acquired acquired;
        try {
            acquired = environments.acquire(check, workspace);
        } catch (CheckEnvironmentUnavailableException e) {
            return new Verdict.CannotVerify(e.getMessage() != null ? e.getMessage() : e.toString(), "");
        }
        try (acquired) {
            TaskExecutionEnvironment environment = acquired.environment();
            String findingsPath = environment.scratchRoot() + "/findings-" + UUID.randomUUID() + ".json";
            CommandProcessRunner.CommandOutcome outcome = processRunner.run(check, environment, findingsPath);
            if (outcome == null) {
                return new Verdict.CannotVerify("failed to start command: " + check.command(), "");
            }

            // The termination decides first (FR6, FR12 of bound-subprocess-commands): a run that
            // never chose an exit code has no findings worth reading, and the read itself would
            // fail on the interrupted path — an in-box read is a supervised docker exec, and the
            // interrupt flag the wait restored makes it throw instead of answering.
            if (!(outcome.termination() instanceof ExecHandle.Wait.Exited)) {
                return unfinished(outcome);
            }
            byte[] findings =
                    environment.readFile(findingsPath, FINDINGS_READ_CAP_BYTES).orElse(null);
            return classify(outcome, findings);
        }
    }

    /**
     * Classifies a run that ended without ever choosing an exit code (FR12, FR6 of
     * bound-subprocess-commands), from its termination alone — the findings channel is never
     * consulted here. A check killed on the installation's timeout is a quality failure — the
     * command ran and failed to finish, exactly as a red exit code would have failed it — carrying
     * the tail captured so far as its one synthetic finding; any findings file it left behind is
     * half-written by construction. An interrupted check is infrastructure: the factory was shut
     * down mid-check, and nothing about the work is known.
     */
    private static Verdict unfinished(CommandProcessRunner.CommandOutcome outcome) {
        if (outcome.termination() instanceof ExecHandle.Wait.TimedOut) {
            return new Verdict.Fail(List.of(new Finding(
                    "command timed out before it exited and its process tree was killed", null, outcome.outputTail())));
        }
        return new Verdict.CannotVerify("command run was interrupted before a verdict existed", outcome.outputTail());
    }

    /**
     * Classifies a completed run's exit code per the engine's Pass/Fail/CannotVerify table (FR7,
     * D6): 0 is a pass — findings content is ignored with a warning (FR8); 126/127 are the
     * shell's "not executable" / "not found" conventions and are treated as infrastructure
     * failures, the findings channel playing no role; any other non-zero exit is a quality
     * failure carrying either the structured findings the command wrote, or one synthetic
     * finding built from the output tail if none were written or they were malformed (FR8,
     * NFR-R2).
     */
    private static Verdict classify(CommandProcessRunner.CommandOutcome outcome, byte @Nullable [] findingsContent) {
        int exitCode = outcome.exitCode();
        if (exitCode == 0) {
            FindingsFileReader.warnIfIgnoredOnPass(findingsContent);
            return new Verdict.Pass();
        }
        if (exitCode == 126 || exitCode == 127) {
            String reason = exitCode == 126 ? "command not executable (exit 126)" : "command not found (exit 127)";
            return new Verdict.CannotVerify(reason, outcome.outputTail());
        }
        Finding syntheticFinding = new Finding("command exited with status " + exitCode, null, outcome.outputTail());
        List<Finding> parsed = FindingsFileReader.read(findingsContent);
        return new Verdict.Fail(parsed != null ? parsed : List.of(syntheticFinding));
    }
}
