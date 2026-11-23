package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor;
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer;
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.executorcontract.SettingsViolation;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Claude dialect's answer step (FR3, FR4 of extract-agent-round-runner): the reply of a {@code
 * verdict} or {@code decision} round is the {@code structured_output} of its result event — the CLI
 * validated it against the {@link ClaudeAnswerSchema} it was handed — or, when the result event
 * carries none (a CLI that ignored the flag), the object of its final text read by {@link
 * FinalTextObject}, logged at WARN. Either way the reply is judged against the same schema, by the
 * factory's one JSON Schema judge ({@link SettingsSchema}), and only an accepted reply is converted
 * — a verdict into the contract's SARIF verdict ({@link JudgeVerdictExtractor#sarif}), a decision
 * into its {@code select} — and written to the answer file through the contract's answer writer. A
 * reply that fails the schema, a final text with no object, and a round whose structured-output
 * retries ran out are a malformed answer, never a pass, and write no file; an unreadable final text
 * is quoted in the malformed answer's reason, through the log exit, so the one operator line of a
 * judge that cannot verify shows what the model wrote on one line.
 *
 * <p>A {@code transform} round's answer is the file the agent wrote itself. The answer file is the
 * factory's own scratch file, written through the shared {@link AtomicFileWriter} so the result
 * reader sees the whole answer or none (FR5 of harden-task-branch-contract); a failure to write it is
 * the factory's, thrown.
 *
 * <p>Implements FR3, FR4 of extract-agent-round-runner.
 */
final class ClaudeAnswer {

    /** The result subtype of a round whose structured output never matched its schema. */
    static final String RETRIES_EXHAUSTED_SUBTYPE = "error_max_structured_output_retries";

    /** The name a verdict's SARIF run is attributed to. */
    static final String TOOL = "claude";

    /**
     * How much of an unreadable final text a malformed answer's reason quotes: the model's own words
     * are what the operator needs to see why no answer could be read, and the reason leaves for the
     * log through its exit, flattened to one line (factory-logging, "The judge-verdict warning is
     * flattened by the choke point").
     */
    static final int FINAL_TEXT_QUOTE_CHARS = 256;

    private static final Logger log = LoggerFactory.getLogger(ClaudeAnswer.class);

    private ClaudeAnswer() {}

    /**
     * What the round's reply to {@code request} becomes.
     *
     * @param request the run; never null
     * @param events the round's events, its result event among them; never null
     * @param answerFile where an accepted answer is written; never null
     * @return the answer; never null
     */
    static DialectAnswer of(ExecutorRequest request, ParsedEvents<AgentEvent> events, Path answerFile) {
        Optional<ObjectNode> schema = ClaudeAnswerSchema.of(request);
        AgentEvent.ResultEvent result = events.last(AgentEvent.ResultEvent.class);
        if (schema.isEmpty() || result == null) {
            return new DialectAnswer.NotWritten();
        }
        JsonNode reply = result.structuredOutput();
        if (reply == null || reply.isNull()) {
            if (RETRIES_EXHAUSTED_SUBTYPE.equals(result.subtype())) {
                return malformed("the CLI exhausted its structured-output retries");
            }
            log.warn(
                    OperatorEvent.AGENT_ANSWER_FROM_FINAL_TEXT.head()
                            + "the agent CLI reported no structured output for the {} answer;"
                            + " reading it from the final text",
                    request.role().wire());
            UntrustedText finalText = result.result();
            switch (FinalTextObject.of(finalText)) {
                case FinalTextObject.Reading.NoObject ignored -> {
                    return malformed("its final text holds no JSON object", finalText);
                }
                case FinalTextObject.Reading.NotJson ignored -> {
                    return malformed("its final text's object is not valid JSON", finalText);
                }
                case FinalTextObject.Reading.Parsed parsed -> reply = parsed.object();
            }
        }
        Optional<UntrustedText> refusal = judged(schema.get(), reply);
        if (refusal.isPresent()) {
            return new DialectAnswer.Malformed(refusal.get());
        }
        write(answerFile, request.role() == Role.VERDICT ? JudgeVerdictExtractor.sarif(reply, TOOL) : select(reply));
        return new DialectAnswer.Written();
    }

    /**
     * Why {@code reply} fails {@code schema}, or empty when the schema accepts it; a schema the judge
     * cannot use accepts nothing.
     */
    static Optional<UntrustedText> judged(JsonNode schema, JsonNode reply) {
        if (!(reply instanceof ObjectNode object)) {
            return Optional.of(UntrustedText.factory("its reply is not a JSON object"));
        }
        return switch (SettingsSchema.judge(schema, ExecutorContractJson.settingsValues(object))) {
            case SettingsJudgement.Accepted ignored -> Optional.empty();
            case SettingsJudgement.Rejected rejected ->
                Optional.of(violation(rejected.violations().getFirst()));
            case SettingsJudgement.Unusable unusable -> Optional.of(unusable.reason());
        };
    }

    /** The violation's location and the validator's words, which left their carrier through its log exit. */
    private static UntrustedText violation(SettingsViolation violation) {
        return UntrustedText.factory(
                violation.path() + ": " + violation.explanation().forLog());
    }

    private static Answer.Select select(JsonNode reply) {
        List<UntrustedText> ranking = new ArrayList<>();
        reply.path("ranking").forEach(option -> ranking.add(UntrustedText.agent(option.asText())));
        JsonNode rationale = reply.get("rationale");
        return new Answer.Select(
                UntrustedText.agent(reply.path("chosen").asText()),
                ranking,
                rationale == null ? null : UntrustedText.agent(rationale.asText()));
    }

    private static void write(Path answerFile, Answer answer) {
        try {
            AtomicFileWriter.write(answerFile, ExecutorContractJson.writer().writeValueAsString(answer));
        } catch (IOException failed) {
            throw new UncheckedIOException("could not write the round's answer file " + answerFile, failed);
        }
    }

    private static DialectAnswer malformed(String reason) {
        return new DialectAnswer.Malformed(UntrustedText.factory(reason));
    }

    /** A malformed answer whose reason quotes the final text it could not read, through the log exit. */
    private static DialectAnswer malformed(String reason, UntrustedText finalText) {
        return malformed(reason + "; the final text was: " + finalText.excerpt(FINAL_TEXT_QUOTE_CHARS));
    }
}
