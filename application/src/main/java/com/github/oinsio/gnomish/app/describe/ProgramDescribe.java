package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.sandbox.BoundedCapture;
import com.github.oinsio.gnomish.sandbox.EgressEntries;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One {@code describe} of one {@code program} executor (design D3, D5 of define-executor-contract):
 * the program's argv with the operation {@code describe} appended, run through the environment's
 * one process seam with the startup bound, its answer written to the factory-named file {@code
 * GNOMISH_RESULT_FILE} under the environment's scratch area and read through the one result-file
 * reader (cap, untrusted text); its stdout and stderr, merged, are a capped log read through the
 * one bounded capture ({@link BoundedCapture}) and never parsed.
 *
 * <p>Every way the program fails to answer is one {@link ExecutorDescribeRefusedException}, thrown
 * without a log line and converted by the preflight into the located error the task parks with; an interrupted wait is not one — the run is being shut down, so it is reported
 * by name with the interrupt flag left set.
 *
 * <p>Implements FR4, FR5, FR6 of define-executor-contract.
 */
@UntrustedParser
final class ProgramDescribe {

    /** The describe answer's cap: 64 KiB, room for a settings schema. */
    static final int DESCRIBE_FILE_CAP_BYTES = 64 * 1024;

    /** The most bytes of the program's log output kept for a refusal's detail. */
    static final int LOG_CAP_BYTES = 8 * 1024;

    private static final ResultFileReader READER =
            new ResultFileReader(DESCRIBE_FILE_CAP_BYTES, UntrustedText::subprocess);

    private final InstantSource clock;
    private final Duration bound;

    /**
     * @param clock the read-time source of the bounded wait; never null
     * @param bound how long a describe may take before it is killed; never null, positive
     */
    ProgramDescribe(InstantSource clock, Duration bound) {
        this.clock = clock;
        this.bound = bound;
    }

    /**
     * Describes {@code declaration} in {@code environment}.
     *
     * @return the program's answer; never null
     * @throws ExecutorDescribeRefusedException when the program does not answer
     * @throws UncheckedIOException when the wait was interrupted (cause {@link InterruptedIOException})
     */
    Describe describe(TaskExecutionEnvironment environment, ExecutorDeclaration declaration) {
        String name = declaration.name();
        String resultFile = ProgramChannel.freshPath(environment, name, ProgramChannel.DESCRIBE);
        List<String> argv = ProgramChannel.argv(declaration, ProgramChannel.DESCRIBE);
        ExecHandle handle;
        try {
            handle = environment.exec(new ExecCommand(
                    argv, Map.of(ProgramChannel.RESULT_FILE_VARIABLE, resultFile), null, StderrPolicy.MERGED));
        } catch (ProcessStartException absent) {
            throw ExecutorDescribeRefusedException.external(
                    name,
                    "the environment refused to start the program",
                    UntrustedText.subprocess(String.valueOf(absent.getMessage())));
        }
        BoundedCapture log = BoundedCapture.of(handle, "describe-output", LOG_CAP_BYTES, bound, clock);
        UntrustedText output = log.output();
        return switch (log.ended()) {
            case ExecHandle.Wait.Interrupted ignored ->
                throw new UncheckedIOException(new InterruptedIOException(
                        "describe of executor '" + name + "' was interrupted and its process tree killed"));
            case ExecHandle.Wait.TimedOut ignored ->
                throw ExecutorDescribeRefusedException.external(
                        name, "describe did not exit within " + bound + " and was killed", output);
            case ExecHandle.Wait.Exited ignored -> answer(environment, name, resultFile, handle.waitForExit(), output);
        };
    }

    private static Describe answer(
            TaskExecutionEnvironment environment, String name, String resultFile, int exitCode, UntrustedText output) {
        if (exitCode != 0) {
            throw ExecutorDescribeRefusedException.configuration(name, "describe exited " + exitCode, output);
        }
        return switch (READER.readJson(limit -> environment.readFile(resultFile, limit), Describe.class)) {
            case ReadResult.Parsed<Describe> parsed -> inert(name, parsed.value());
            case ReadResult.Absent<Describe> ignored ->
                throw ExecutorDescribeRefusedException.configuration(
                        name, "describe wrote no answer to " + ProgramChannel.RESULT_FILE_VARIABLE, output);
            case ReadResult.Truncated<Describe> ignored ->
                throw ExecutorDescribeRefusedException.configuration(
                        name, "the describe answer exceeds " + DESCRIBE_FILE_CAP_BYTES + " bytes", null);
            case ReadResult.Malformed<Describe> malformed ->
                throw ExecutorDescribeRefusedException.configuration(
                        name, "the describe answer is malformed", malformed.reason());
        };
    }

    /**
     * Refuses an answer whose free text carries a control character (wire-contract "Describe output
     * is untrusted"): the version, an egress host and a policy-disabling flag are each quoted to the
     * operator and handed on, and a control character in any of them is no value the program could
     * mean. Credential and configuration-directory names were already held to the variable grammar.
     * An egress host is then held to the one host grammar the guard renders ({@link EgressEntries}),
     * so the law's hosts are compared with the describe's in one spelling.
     */
    private static Describe inert(String name, Describe describe) {
        List<String> texts = new ArrayList<>();
        texts.add(describe.version().forParsing());
        texts.addAll(describe.egress());
        texts.addAll(describe.policyDisablingFlags());
        if (texts.stream().anyMatch(ProgramDescribe::hasControl)) {
            throw ExecutorDescribeRefusedException.configuration(
                    name, "the describe answer carries a control character in a text field", null);
        }
        for (String host : describe.egress()) {
            if (!EgressEntries.isEntry(host)) {
                throw ExecutorDescribeRefusedException.configuration(
                        name,
                        "its describe declares an egress host that is not a host name, an IP literal or a *.suffix"
                                + " wildcard",
                        UntrustedText.subprocess(host));
            }
        }
        return describe;
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }
}
