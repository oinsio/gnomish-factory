package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.function.Function;

/**
 * How a written answer document becomes an {@link Answer}: the kind selects the reading, the
 * fields are read by their JSON type, and the answer types' own invariants judge the values. Any
 * refusal on the way — a missing kind, an unknown one, a field of the wrong type, a decision request
 * with fewer than two options, a verdict that is not SARIF 2.1.0 — is a {@link
 * AnswerReading.Malformed} carrying the refusal as its reason. Every text the answer carries, and
 * the reason, which quotes the document, is minted in the writer's family (NFR-S2).
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
final class AnswerReader {

    private AnswerReader() {}

    static AnswerReading read(JsonNode written, Function<String, UntrustedText> mint) {
        try {
            return new AnswerReading.Parsed(answer(written, new WrittenTexts(mint)));
        } catch (IllegalArgumentException malformed) {
            return new AnswerReading.Malformed(mint.apply(String.valueOf(malformed.getMessage())));
        }
    }

    private static Answer answer(JsonNode written, WrittenTexts texts) {
        if (!written.isObject()) {
            throw new IllegalArgumentException("the answer is not a JSON object");
        }
        String token = WrittenNodes.text(written, "kind", "the answer");
        AnswerKind kind = AnswerKind.fromWire(token)
                .orElseThrow(() -> new IllegalArgumentException("unknown answer kind '" + token + "'"));
        return switch (kind) {
            case VERDICT -> SarifReader.verdict(written.path("sarif"), texts);
            case SELECT ->
                new Answer.Select(
                        texts.text(written, "chosen", "the select answer"),
                        texts.texts(written, "ranking", "the select answer"),
                        texts.optionalText(written, "rationale", "the select answer"));
            case DECISION_REQUEST ->
                new Answer.DecisionRequest(
                        texts.text(written, "question", "the decision-request answer"),
                        texts.texts(written, "options", "the decision-request answer"),
                        WrittenNodes.optionalText(written, "continuationKey", "the decision-request answer"));
        };
    }
}
