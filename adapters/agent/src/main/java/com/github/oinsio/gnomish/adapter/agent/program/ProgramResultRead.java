package com.github.oinsio.gnomish.adapter.agent.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.executorcontract.ResultSource;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

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
 * <p>The answer file's read is shared with the agent round runner ({@link #answerIn}, {@link
 * #answerOnHost}): one cap and one wording for every answer file the factory reads back.
 *
 * <p>Implements FR5, FR6, NFR-R1, NFR-S2 of define-executor-contract; FR1 of extract-agent-round-runner.
 */
public final class ProgramResultRead {

    /** The result file's cap: one MiB. */
    static final int RESULT_FILE_CAP_BYTES = 1024 * 1024;

    /** The answer file's cap: four MiB, room for a SARIF log at its result cap. */
    static final int ANSWER_FILE_CAP_BYTES = 4 * 1024 * 1024;

    private static final ResultFileReader RESULT_READER =
            new ResultFileReader(RESULT_FILE_CAP_BYTES, UntrustedText::subprocess);

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
        return switch (answerIn(environment, answerFile, UntrustedText::subprocess)) {
            case AnswerRead.None ignored -> result;
            case AnswerRead.Read read -> result.withAnswer(read.answer());
            case AnswerRead.Refused refused -> context.failure(refused.cause());
        };
    }

    /**
     * Reads an answer file in the run's environment through the one capped result reader, at the
     * answer file's cap: the {@code program} run's answer file, and the agent round's answer file the
     * agent wrote itself (FR1 of extract-agent-round-runner).
     *
     * @param environment the environment the run wrote the file in; never null
     * @param answerFile the factory-named answer path; never null
     * @param mint the family the file's text is minted in: the writer's; never null
     * @return the answer, its absence, or why it is refused; never null
     */
    public static AnswerRead answerIn(
            TaskExecutionEnvironment environment, String answerFile, Function<String, UntrustedText> mint) {
        return answer(limit -> environment.readFile(answerFile, limit), mint);
    }

    /**
     * Reads an answer file on the host through the one capped result reader, at the answer file's
     * cap: the agent round's answer a dialect extracted from the stream and wrote itself (FR1 of
     * extract-agent-round-runner).
     *
     * @param answerFile the host path; never null
     * @param mint the family the file's text is minted in: the writer's; never null
     * @return the answer, its absence, or why it is refused; never null
     */
    public static AnswerRead answerOnHost(Path answerFile, Function<String, UntrustedText> mint) {
        return answer(ResultSource.hostFile(answerFile), mint);
    }

    private static AnswerRead answer(ResultSource source, Function<String, UntrustedText> mint) {
        ReadResult<JsonNode> read = new ResultFileReader(ANSWER_FILE_CAP_BYTES, mint).readJson(source, JsonNode.class);
        return switch (read) {
            case ReadResult.Absent<JsonNode> ignored -> new AnswerRead.None();
            case ReadResult.Parsed<JsonNode> parsed -> new AnswerRead.Read(parsed.value());
            case ReadResult.Malformed<JsonNode> malformed ->
                new AnswerRead.Refused(
                        "wrote a malformed answer file: " + malformed.reason().excerpt(512));
            case ReadResult.Truncated<JsonNode> ignored ->
                new AnswerRead.Refused("wrote an answer file over the cap of " + ANSWER_FILE_CAP_BYTES + " bytes");
        };
    }

    /** What an answer file held: nothing, an answer, or a reason the factory refuses it. */
    public sealed interface AnswerRead {

        /** No answer file was written. */
        record None() implements AnswerRead {}

        /**
         * The answer.
         *
         * @param answer the file's JSON; never null
         */
        record Read(JsonNode answer) implements AnswerRead {}

        /**
         * A file that cannot be taken as an answer.
         *
         * @param cause the factory's sentence, any quoted content already through an exit; never blank
         */
        record Refused(String cause) implements AnswerRead {}
    }

    private static ExecutorResult withWallTime(ExecutorResult result, Duration wallTime) {
        Usage usage = result.usage();
        if (usage.wallMillis() != null) {
            return result;
        }
        Usage measured = new Usage(usage.provenance(), wallTime.toMillis(), usage.models(), usage.tools());
        return result.withUsage(measured);
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
            return ProgramFailures.infrastructure(
                    ProgramFailures.Subject.PROGRAM, executor, cause, stderr.tailForFailure());
        }
    }
}
