package com.github.oinsio.gnomish.app.port.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.AnswerReading;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.executorcontract.ResultSource;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.function.Function;

/**
 * The one read of an answer file (FR21, NFR-R5 of extract-agent-round-runner; design D15, part 4,
 * and the single-owner row "The answer read" of D8): an agent round's answer file, a {@code program}
 * run's or a built-in's, read live, and the answer file a snapshot carries, read at the recovery of
 * an interrupted verification. Each caller hands its medium over — the run's environment or a
 * blob of the registered commit as a {@link ResultSource} — and the bytes go through the
 * executor contract's {@link ResultFileReader} at {@link ResultFileReader#ANSWER_FILE_CAP_BYTES},
 * then through the contract's {@link Answer#read}. So the cap, the parse and the refusals are one
 * rule for every medium and for live and recovery alike: the same bytes give the same {@link
 * AnswerFileReading}.
 *
 * <p>No fallback: an empty file (or one of white space only), text that is not JSON, a file over
 * the cap and a document the contract refuses (an unknown kind, a decision request with fewer than
 * two options) are each {@link AnswerFileReading.Refused} with its cause, an empty file named as
 * empty; nothing turns the text into a question.
 *
 * <p>Implements FR20, FR21, NFR-R5 of extract-agent-round-runner; FR6 of define-executor-contract.
 */
public final class AnswerFileRead {

    /** How much of a refused file's text a cause quotes. */
    static final int CAUSE_EXCERPT_CHARS = 512;

    private AnswerFileRead() {}

    /**
     * Reads the answer file at {@code path} in the run's environment, through its channel, so a read
     * inside a box stays a box read.
     *
     * @param environment the environment the run wrote the file in; never null
     * @param path the factory-named answer path, as the environment's channel resolves it; never null
     * @param mint the family the file's text is minted in: its writer's; never null
     * @return the reading; never null
     */
    public static AnswerFileReading in(
            TaskExecutionEnvironment environment, String path, Function<String, UntrustedText> mint) {
        return read(limit -> environment.readFile(path, limit), mint);
    }

    /**
     * Reads the answer file behind {@code source}.
     *
     * @param source the file on its own medium; never null
     * @param mint the family the file's text is minted in: its writer's; never null
     * @return the reading; never null
     */
    public static AnswerFileReading read(ResultSource source, Function<String, UntrustedText> mint) {
        ResultFileReader reader = new ResultFileReader(ResultFileReader.ANSWER_FILE_CAP_BYTES, mint);
        return switch (reader.readJson(source, JsonNode.class)) {
            case ReadResult.Absent<JsonNode> ignored -> new AnswerFileReading.Absent();
            case ReadResult.Truncated<JsonNode> truncated ->
                new AnswerFileReading.Refused(
                        "wrote an answer file over the cap of " + ResultFileReader.ANSWER_FILE_CAP_BYTES + " bytes",
                        written(truncated.content(), mint));
            case ReadResult.Malformed<JsonNode> malformed -> malformed(malformed);
            case ReadResult.Parsed<JsonNode> parsed -> answer(parsed.value(), mint);
        };
    }

    /** A file that is not one JSON document: empty, or text the parse refused, named as which. */
    private static AnswerFileReading malformed(ReadResult.Malformed<JsonNode> malformed) {
        UntrustedText written = malformed.content();
        String cause = written.isBlank()
                ? "wrote an empty answer file"
                : "wrote a malformed answer file: " + malformed.reason().excerpt(CAUSE_EXCERPT_CHARS);
        return new AnswerFileReading.Refused(cause, written);
    }

    private static AnswerFileReading answer(JsonNode written, Function<String, UntrustedText> mint) {
        return switch (Answer.read(written, mint)) {
            case AnswerReading.Parsed parsed -> new AnswerFileReading.Answered(parsed.answer());
            case AnswerReading.Malformed malformed ->
                new AnswerFileReading.Refused(
                        "wrote an answer file that is no valid answer: "
                                + malformed.reason().excerpt(CAUSE_EXCERPT_CHARS),
                        mint.apply(written.toString()));
        };
    }

    /** The text a cut read took: the marked content, whatever it parsed to. */
    private static UntrustedText written(ReadResult.Content<JsonNode> content, Function<String, UntrustedText> mint) {
        return switch (content) {
            case ReadResult.Malformed<JsonNode> malformed -> malformed.content();
            case ReadResult.Parsed<JsonNode> parsed -> mint.apply(parsed.value().toString());
        };
    }
}
