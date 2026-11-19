package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * What one {@code shell} check run found, before any port renders it (FR5 of
 * migrate-verify-to-executor-contract): the results it reported, a run killed on the check timeout,
 * or the reason no answer exists. The {@link ShellCommandCheckRunner} judges it and the {@link
 * ShellExecutor} renders it as the executor contract's result, its one rendering.
 *
 * <p>The exit code is status, never a verdict (design D5 as amended 2026-11-08): {@link #judge}
 * reads the findings path on every exit but 126/127, and any result written there is the answer,
 * whatever the code; only a red command that wrote no valid result gets the one synthetic {@code
 * error} result carrying its output tail, and a green one an empty answer. 126 and 127 — the shell's
 * "not executable" and "not found" — are no answer at all.
 *
 * <p>Implements FR7, FR8, NFR-R2 of add-manual-run; FR6, FR12 of bound-subprocess-commands; FR5 of
 * migrate-verify-to-executor-contract.
 */
sealed interface ShellOutcome {

    /** The one finding a check killed on the installation's timeout carries. */
    String TIMED_OUT = "command timed out before it exited and its process tree was killed";

    /**
     * Judges a run that started (FR12, FR6 of bound-subprocess-commands; FR5 of
     * migrate-verify-to-executor-contract). A run that never chose an exit code is judged from its
     * termination alone, and its findings file — half-written by construction, and unreadable on
     * the interrupted path, where the restored flag makes an in-box read throw — is never read.
     *
     * @param checkIdentity the check's log-plane identity, for the findings warnings
     * @param outcome how the run ended; never null
     * @param environment the environment the run used, the findings path's reader; never null
     * @param findingsPath the path handed to the command as {@code GNOMISH_FINDINGS_FILE}
     * @return the outcome; never null
     */
    static ShellOutcome judge(
            String checkIdentity,
            CommandProcessRunner.CommandOutcome outcome,
            TaskExecutionEnvironment environment,
            String findingsPath) {
        if (outcome.termination() instanceof ExecHandle.Wait.TimedOut) {
            return new TimedOut(outcome.outputTail());
        }
        UntrustedText tail = UntrustedText.subprocess(outcome.outputTail());
        if (!(outcome.termination() instanceof ExecHandle.Wait.Exited)) {
            return new Unanswerable(
                    UntrustedText.factory("command run was interrupted before a verdict existed"), tail);
        }
        int exitCode = outcome.exitCode();
        if (exitCode == 126 || exitCode == 127) {
            // The reason is the factory's own sentence about an exit code, quoting nothing the
            // command printed (design D3 of type-untrusted-text).
            String reason = exitCode == 126 ? "command not executable (exit 126)" : "command not found (exit 127)";
            return new Unanswerable(UntrustedText.factory(reason), tail);
        }
        List<ShellResult> reported =
                CheckFindings.results(checkIdentity, CheckFindings.read(environment, findingsPath));
        if (!reported.isEmpty() || exitCode == 0) {
            return new Answered(reported);
        }
        return new Answered(List.of(ShellResult.synthetic(exitCode, outcome.outputTail())));
    }

    /**
     * The run answered: these results, in the order written.
     *
     * @param results the results; possibly empty, defensively copied
     */
    record Answered(List<ShellResult> results) implements ShellOutcome {

        public Answered {
            results = List.copyOf(results);
        }
    }

    /**
     * The check timeout expired and the process tree was killed: the command ran and failed to
     * finish, as a red result would have failed it, under every threshold.
     *
     * @param outputTail the tail captured until the kill; never null
     */
    record TimedOut(String outputTail) implements ShellOutcome {}

    /**
     * No answer exists: no environment, a process that never started, an interrupted run, exit 126
     * or 127.
     *
     * @param reason the short cause; never null
     * @param details what was captured beside it, possibly empty; never null
     */
    record Unanswerable(UntrustedText reason, UntrustedText details) implements ShellOutcome {}
}
