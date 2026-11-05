package com.github.oinsio.gnomish.adapter.command;

import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import java.time.Duration;

/**
 * How one {@link CommandRun} ended, with what the run captured — the termination kind first, since
 * only a command that {@link Exited exited} chose an exit code at all. The core classifies nothing:
 * each caller maps these four kinds to its own result (a verify check to a {@code Verdict}, the
 * command executor to a contract status), so a code of 0, 126 or 127 means nothing here (design D2
 * of add-command-executor).
 *
 * <p>Implements FR6, FR7, D2 of add-command-executor.
 */
public sealed interface CommandRunOutcome {

    /**
     * The command exited on its own within the timeout.
     *
     * @param exitCode the code the command chose
     * @param outputTail the bounded tail of the merged stdout/stderr stream; never null
     * @param wallTime the start-to-exit span the handle measured; never null
     */
    record Exited(int exitCode, String outputTail, Duration wallTime) implements CommandRunOutcome {}

    /**
     * The timeout expired first; the command's whole process tree was killed and reaped. The
     * killed tree's exit code is the signal's, so none is reported.
     *
     * @param outputTail the bounded tail captured until the kill; never null
     * @param elapsed how long the command ran before the kill; never null
     */
    record TimedOut(String outputTail, Duration elapsed) implements CommandRunOutcome {}

    /**
     * The waiting thread was interrupted first; the process tree was killed and reaped and the
     * interrupt flag restored. Nothing about the command's work is known.
     *
     * @param outputTail the bounded tail captured until the kill; never null
     * @param elapsed how long the command ran before the kill; never null
     */
    record Interrupted(String outputTail, Duration elapsed) implements CommandRunOutcome {}

    /**
     * The environment could not start the process at all — a missing shell, an unavailable
     * container runtime.
     *
     * @param cause what the environment reported; never null
     */
    record NotStarted(ProcessStartException cause) implements CommandRunOutcome {}
}
