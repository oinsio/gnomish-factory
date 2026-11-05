package com.github.oinsio.gnomish.adapter.agent.command;

import com.github.oinsio.gnomish.adapter.command.CommandRunOutcome;
import com.github.oinsio.gnomish.app.findings.FindingsSanitizer;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.FailureClass;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.executorcontract.UsageProvenance;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The exit-code contract of the {@code command} built-in (FR5, FR6, FR7 of add-command-executor;
 * design D1, D4): how one command run's {@link CommandRunOutcome} — what the shared core observed,
 * unclassified — is reported as the executor contract's result. Exit {@code 0} is {@code completed};
 * {@code 126} (not executable) and {@code 127} (not found) are {@code failed/infrastructure}, the
 * shell's own words for a command that cannot run, as the verify check reads them; any other exit of a
 * started, finished process is {@code failed/quality}. A process that could not be started, that
 * outlived its {@code roundTimeout} and had its tree killed, or whose wait was interrupted (its tree
 * killed and the flag left set by the core) has no verdict, so it is {@code failed/infrastructure}: an
 * interruption is reported as one, never as an expired bound — the precedent of the program executor
 * and of the agent round ("An interrupted wait is not blamed on the bound", {@code
 * executor/agent-executor}). The class says nothing beyond the status; what it costs the attempt is the
 * engine's classifier's.
 *
 * <p>Every reason names the command, so an infrastructure failure escalates as "cannot execute" naming
 * it (the contract's mapping hands the engine the reason verbatim), and a quality reason is the round's
 * one finding: "exit code N" and the tail of the command's own output, stripped of control sequences
 * and bounded by {@link FindingsSanitizer} — the findings funnel's sanitizer, which keeps the output's
 * line structure (NFR-O1, UX1). A reason that quotes the captured tail keeps the capture's family
 * ({@code subprocess}); one that quotes nothing captured is the manifest's command in the factory's
 * sentence ({@code manifest}). Usage is a known zero of model use with the run's wall time (NFR-C1,
 * design D8): provenance {@code REPORTED}, no model and no tool — never {@code ABSENT}, which means the
 * executor could not tell and would mark the task's cost incomplete in the usage fold.
 *
 * <p>Implements FR5, FR6, FR7, NFR-C1, NFR-O1, UX1 of add-command-executor.
 */
final class CommandRoundReport {

    /** Exit code of a command the shell found but could not execute. */
    static final int NOT_EXECUTABLE = 126;

    /** Exit code of a command the shell could not find. */
    static final int NOT_FOUND = 127;

    /**
     * How many characters of the captured tail a reason quotes, after stripping: the core already keeps
     * at most 200 lines or 10 KB, and this bound keeps one finding readable in an escalation report.
     */
    static final int TAIL_CAP_CHARS = 4096;

    /** How much of an environment's refusal to start the process a reason quotes. */
    private static final int REFUSAL_EXCERPT_CHARS = 512;

    private CommandRoundReport() {}

    /**
     * The contract's result of one run of {@code command}.
     *
     * @param command the declared command, as the stage manifest wrote it; never null
     * @param timeout the round's resolved bound; never null
     * @param outcome what the run observed; never null
     * @return the result; never null
     */
    static ExecutorResult of(String command, Duration timeout, CommandRunOutcome outcome) {
        String named = "command '" + command + "'";
        return switch (outcome) {
            case CommandRunOutcome.Exited exited
            when exited.exitCode() == 0 ->
                new ExecutorResult(Status.COMPLETED, null, null, null, null, usage(exited.wallTime()), List.of());
            case CommandRunOutcome.Exited exited -> exited(named, exited);
            case CommandRunOutcome.TimedOut timedOut ->
                failed(
                        FailureClass.INFRASTRUCTURE,
                        quoting(
                                named + " outlived its roundTimeout of " + timeout + " and its process tree was killed",
                                timedOut.outputTail()),
                        timedOut.elapsed());
            case CommandRunOutcome.Interrupted interrupted ->
                failed(
                        FailureClass.INFRASTRUCTURE,
                        quoting(named + " was interrupted and its process tree was killed", interrupted.outputTail()),
                        interrupted.elapsed());
            case CommandRunOutcome.NotStarted notStarted ->
                failed(
                        FailureClass.INFRASTRUCTURE,
                        UntrustedText.manifest(named + " could not be started: "
                                + UntrustedText.subprocess(String.valueOf(
                                                notStarted.cause().getMessage()))
                                        .excerpt(REFUSAL_EXCERPT_CHARS)),
                        null);
        };
    }

    /**
     * A {@code failed/infrastructure} result in the factory's words about the command, for a run the
     * built-in refuses before any process exists.
     *
     * @param reason the factory's sentence; never blank
     * @return the result; never null
     */
    static ExecutorResult refused(String reason) {
        return failed(FailureClass.INFRASTRUCTURE, UntrustedText.manifest(reason), null);
    }

    private static ExecutorResult exited(String named, CommandRunOutcome.Exited exited) {
        int code = exited.exitCode();
        if (code == NOT_EXECUTABLE || code == NOT_FOUND) {
            String meaning = code == NOT_EXECUTABLE ? "not executable" : "not found";
            return failed(
                    FailureClass.INFRASTRUCTURE,
                    quoting(named + " cannot execute: exit code " + code + " (" + meaning + ")", exited.outputTail()),
                    exited.wallTime());
        }
        return failed(
                FailureClass.QUALITY,
                quoting(named + " failed with exit code " + code, exited.outputTail()),
                exited.wallTime());
    }

    /** The sentence, and the sanitized, bounded tail when the command printed anything. */
    private static UntrustedText quoting(String sentence, String outputTail) {
        String tail = FindingsSanitizer.capTail(FindingsSanitizer.strip(outputTail), TAIL_CAP_CHARS);
        if (tail.isBlank()) {
            return UntrustedText.manifest(sentence + "; it printed nothing");
        }
        return UntrustedText.subprocess(sentence + "; its output ended with:\n" + tail);
    }

    private static ExecutorResult failed(FailureClass failureClass, UntrustedText reason, @Nullable Duration wall) {
        return new ExecutorResult(Status.FAILED, failureClass, null, reason, null, usage(wall), List.of());
    }

    private static Usage usage(@Nullable Duration wall) {
        return new Usage(UsageProvenance.REPORTED, wall == null ? null : wall.toMillis(), Map.of(), List.of());
    }
}
