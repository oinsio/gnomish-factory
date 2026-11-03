package com.github.oinsio.gnomish.adapter.agent.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;

/**
 * Reads the result of a {@code program} run that exited (FR6, NFR-R1 of define-executor-contract):
 * the result file {@code GNOMISH_RESULT_FILE} named, and — when the result's own answer slot is
 * empty — the answer file {@code GNOMISH_ANSWER_FILE} named, each through the one capped result
 * reader and the environment's channel, so a read inside a box stays a box read. The exit code
 * means nothing to the engine (FR7): a valid result is the result whatever the code, and the code is
 * named only when no result can be read.
 *
 * <p>A file that cannot be read as a result is the run's {@code failed/infrastructure}: absent,
 * over the cap with the cut content unreadable, or malformed — naming what was wrong. A result that
 * reports no wall time takes the one the factory measured. Nothing here deletes or rewrites a file,
 * so a re-read of the same files reads the same result (NFR-R1).
 *
 * <p>Implements FR5, FR6, NFR-R1, NFR-S2 of define-executor-contract.
 */
final class ProgramResultRead {

    /** The result file's cap: one MiB. */
    static final int RESULT_FILE_CAP_BYTES = 1024 * 1024;

    /** The answer file's cap: four MiB, room for a SARIF log at its result cap. */
    static final int ANSWER_FILE_CAP_BYTES = 4 * 1024 * 1024;

    private static final ResultFileReader RESULT_READER =
            new ResultFileReader(RESULT_FILE_CAP_BYTES, UntrustedText::subprocess);

    private static final ResultFileReader ANSWER_READER =
            new ResultFileReader(ANSWER_FILE_CAP_BYTES, UntrustedText::subprocess);

    private ProgramResultRead() {}

    /**
     * The run's result.
     *
     * @param environment the environment the run wrote its files in; never null
     * @param resultFile the factory-named result path; never null
     * @param answerFile the factory-named answer path; never null
     * @param exited how the process exited; never null
     * @param context who ran and what its stderr said last, for a failure's reason; never null
     * @return the result, or the run's {@code failed/infrastructure}; never null
     */
    static ExecutorResult read(
            TaskExecutionEnvironment environment,
            String resultFile,
            String answerFile,
            ProgramEnd.Exited exited,
            FailureContext context) {
        ReadResult<ExecutorResult> read =
                RESULT_READER.readJson(limit -> environment.readFile(resultFile, limit), ExecutorResult.class);
        ExecutorResult result =
                switch (read) {
                    case ReadResult.Absent<ExecutorResult> ignored ->
                        context.failure("exited " + exited.exitCode() + " and wrote no result to "
                                + ProgramChannel.RESULT_FILE_VARIABLE);
                    case ReadResult.Truncated<ExecutorResult> truncated ->
                        content(truncated.content(), context, " (cut at " + RESULT_FILE_CAP_BYTES + " bytes)");
                    case ReadResult.Content<ExecutorResult> whole -> content(whole, context, "");
                };
        if (result.answer() == null && read instanceof ReadResult.Parsed<ExecutorResult>) {
            result = withAnswerFile(environment, answerFile, result, context);
        }
        return withWallTime(result, exited.wallTime());
    }

    private static ExecutorResult content(
            ReadResult.Content<ExecutorResult> content, FailureContext context, String cut) {
        return switch (content) {
            case ReadResult.Parsed<ExecutorResult> parsed -> parsed.value();
            case ReadResult.Malformed<ExecutorResult> malformed ->
                context.failure("wrote a malformed result" + cut + ": "
                        + malformed.reason().excerpt(512));
        };
    }

    private static ExecutorResult withAnswerFile(
            TaskExecutionEnvironment environment, String answerFile, ExecutorResult result, FailureContext context) {
        ReadResult<JsonNode> read =
                ANSWER_READER.readJson(limit -> environment.readFile(answerFile, limit), JsonNode.class);
        return switch (read) {
            case ReadResult.Absent<JsonNode> ignored -> result;
            case ReadResult.Parsed<JsonNode> parsed ->
                new ExecutorResult(
                        result.status(),
                        result.failureClass(),
                        result.pendingKey(),
                        result.reason(),
                        parsed.value(),
                        result.usage(),
                        result.denials());
            case ReadResult.Malformed<JsonNode> malformed ->
                context.failure(
                        "wrote a malformed answer file: " + malformed.reason().excerpt(512));
            case ReadResult.Truncated<JsonNode> ignored ->
                context.failure("wrote an answer file over the cap of " + ANSWER_FILE_CAP_BYTES + " bytes");
        };
    }

    private static ExecutorResult withWallTime(ExecutorResult result, Duration wallTime) {
        Usage usage = result.usage();
        if (usage.wallMillis() != null) {
            return result;
        }
        Usage measured = new Usage(usage.provenance(), wallTime.toMillis(), usage.models(), usage.tools());
        return new ExecutorResult(
                result.status(),
                result.failureClass(),
                result.pendingKey(),
                result.reason(),
                result.answer(),
                measured,
                result.denials());
    }

    /** Who ran and what its stderr said last: what a failure's reason names. */
    static final class FailureContext {

        private final String executor;
        private final RoundScratchDirectory stderr;

        /**
         * @param executor the executor's name; never blank
         * @param stderr the directory holding the run's stderr, whose tail a failure quotes, read only
         *     when a failure is reported; never null
         */
        FailureContext(String executor, RoundScratchDirectory stderr) {
            this.executor = executor;
            this.stderr = stderr;
        }

        ExecutorResult failure(String cause) {
            return ProgramFailures.infrastructure(executor, cause, stderr.tailForFailure());
        }
    }
}
