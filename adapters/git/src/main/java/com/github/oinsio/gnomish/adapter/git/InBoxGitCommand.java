package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.sandbox.CapturedExec;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The container medium's one mapping of a native in-box invocation outcome onto the named
 * termination taxonomy (design D14): {@link GitCommandResult} carries it for the host git runner,
 * and this carries it for every git command the factory runs <em>inside</em> a task environment.
 *
 * <p>The native representation is awkward on purpose — {@link CapturedExec} reports an interrupted
 * wait by throwing an {@link UncheckedIOException} whose cause is an {@link InterruptedIOException}
 * — and that awkwardness is exactly what must not spread: a call site that catches the exception
 * itself is branching on an exception type to decide a retry policy, which is the scatter D3
 * removes from classification and D14 removes from invocation outcomes. So this seam does it once,
 * and every in-box caller reads {@link Outcome#termination()} instead.
 *
 * <p>Only the interrupt is converted; a broken output stream is still a defect and propagates
 * unchanged, and a start failure is not a termination at all — the command never ran — so {@code
 * ProcessStartException} propagates too, for the caller that degrades on a lost environment.
 *
 * <p>Implements FR6 of harden-task-branch-contract; consumes FR11 of bound-subprocess-commands.
 */
final class InBoxGitCommand {

    private final TaskExecutionEnvironment environment;

    InBoxGitCommand(TaskExecutionEnvironment environment) {
        this.environment = environment;
    }

    /**
     * One in-box invocation's outcome in the shared taxonomy.
     *
     * @param termination how the invocation ended; {@link Termination#EXITED} is the only value
     *     that makes {@code exitCode} authoritative
     * @param exitCode the code the command chose, or {@code -1} when it never got to choose one
     * @param output everything the command wrote, or the interrupt's message when it was cut short
     */
    record Outcome(Termination termination, int exitCode, String output) {

        /** Whether the command ran to its own exit and reported success. */
        boolean succeeded() {
            return termination == Termination.EXITED && exitCode == 0;
        }
    }

    /**
     * Runs {@code script} through {@code sh -c} in the task environment and captures it whole.
     *
     * @param what the operation named in failure messages, e.g. {@code "in-box state commit"}
     * @param script the shell script to run inside the box; never blank
     * @param arguments the positional arguments {@code script} reads, if any
     * @return the invocation's outcome; never null
     */
    Outcome run(String what, String script, List<String> arguments) {
        List<String> argv = new ArrayList<>(List.of("sh", "-c", script));
        argv.addAll(arguments);
        try {
            CapturedExec captured =
                    CapturedExec.of(environment.exec(new ExecCommand(argv, Map.of(), null, true)), what);
            return new Outcome(Termination.EXITED, captured.exitCode(), captured.output());
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof InterruptedIOException interrupted) {
                return new Outcome(Termination.INTERRUPTED, -1, String.valueOf(interrupted.getMessage()));
            }
            throw e;
        }
    }
}
