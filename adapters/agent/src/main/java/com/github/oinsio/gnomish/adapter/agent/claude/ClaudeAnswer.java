package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.agent.JudgeVerdictExtractor;
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer;
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.executorcontract.SettingsViolation;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Claude dialect's answer step (FR3, FR4, FR19 of extract-agent-round-runner; design D15): the
 * reply of a round in every role the dialect declares is the {@code structured_output} of its result
 * event — the CLI validated it against the {@link ClaudeAnswerSchema} it was handed — or, when the
 * result event carries none (a CLI that ignored the flag), the object of its final text read by
 * {@link FinalTextObject}, logged at WARN. Either way the reply is judged against the same schema, by
 * the factory's one JSON Schema judge ({@link SettingsSchema}), and only an accepted reply is
 * converted into the contract answer it states — a verdict into the contract's SARIF verdict ({@link
 * JudgeVerdictExtractor#sarif}), a decision into its {@code select}, a {@code transform} reply that
 * asks a decision into its {@code decision-request}, one that is done into no answer. The runner
 * writes that answer to the round's answer path and reads it back through the one answer read, which
 * holds what the schema subset cannot carry (a decision request's two options). A reply that fails the
 * schema, a final text with no object, and a round whose structured-output retries ran out are a
 * malformed answer, never a pass; an unreadable final text is quoted in the malformed answer's reason,
 * through the log exit, so the one operator line of a judge that cannot verify shows what the model
 * wrote on one line.
 *
 * <p>Implements FR3, FR4, FR19 of extract-agent-round-runner.
 */
final class ClaudeAnswer {

    /** The result subtype of a round whose structured output never matched its schema. */
    static final String RETRIES_EXHAUSTED_SUBTYPE = "error_max_structured_output_retries";

    /** The contract's kind word of a decision request. */
    static final String DECISION_REQUEST_KIND = "decision-request";

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
     * @return the answer; never null
     */
    static DialectAnswer of(ExecutorRequest request, ParsedEvents<AgentEvent> events) {
        Optional<ObjectNode> schema = ClaudeAnswerSchema.of(request);
        AgentEvent.ResultEvent result = events.last(AgentEvent.ResultEvent.class);
        if (schema.isEmpty() || result == null) {
            return new DialectAnswer.NoReply();
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
        return switch (request.role()) {
            case VERDICT -> replied(JudgeVerdictExtractor.sarif(reply, TOOL));
            case DECISION -> replied(select(reply));
            default -> transform(reply);
        };
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
        String rationale = reply.path("rationale").asText();
        return new Answer.Select(
                UntrustedText.agent(reply.path("chosen").asText()),
                ranking,
                rationale.isBlank() ? null : UntrustedText.agent(rationale));
    }

    /**
     * A {@code transform} reply: done is no answer; a decision asked is the contract's {@code
     * decision-request} document with the reply's question and options as written, which the runner's
     * one read judges against the contract — a request with fewer than two options is refused there,
     * as a file of that shape would be.
     */
    private static DialectAnswer transform(JsonNode reply) {
        if (!ClaudeAnswerSchema.DECISION_REQUEST.equals(reply.path("outcome").asText())) {
            return new DialectAnswer.NoReply();
        }
        ObjectNode request = JsonNodeFactory.instance.objectNode().put("kind", DECISION_REQUEST_KIND);
        request.set("question", reply.get("question"));
        request.set("options", reply.get("options"));
        return new DialectAnswer.Replied(request);
    }

    private static DialectAnswer replied(Answer answer) {
        return new DialectAnswer.Replied(ExecutorContractJson.answerTree(answer));
    }

    private static DialectAnswer malformed(String reason) {
        return new DialectAnswer.Malformed(UntrustedText.factory(reason));
    }

    /** A malformed answer whose reason quotes the final text it could not read, through the log exit. */
    private static DialectAnswer malformed(String reason, UntrustedText finalText) {
        return malformed(reason + "; the final text was: " + finalText.excerpt(FINAL_TEXT_QUOTE_CHARS));
    }
}
