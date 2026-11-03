package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How an {@link Answer} is written: the wire document {@code executor-answer.schema.json} accepts,
 * the inverse of {@link AnswerReader} — an absent ranking, rationale or key, and an absent result
 * rule or location, are left out.
 *
 * <p>A machine writer, and so an exit: every carrier the answer holds is written as the JSON string
 * it was read from, into a document only {@link ResultFileReader} reads back, minting it again
 * (NFR-S2).
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
@UntrustedExit
final class AnswerWriter {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private AnswerWriter() {}

    static ObjectNode write(Answer answer) {
        ObjectNode node = NODES.objectNode();
        switch (answer) {
            case Answer.Verdict verdict -> {
                node.put("kind", AnswerKind.VERDICT.wire());
                node.set("sarif", sarif(verdict));
            }
            case Answer.Select select -> {
                node.put("kind", AnswerKind.SELECT.wire());
                node.put("chosen", select.chosen().raw());
                texts(node, "ranking", select.ranking());
                optional(node, "rationale", select.rationale());
            }
            case Answer.DecisionRequest request -> {
                node.put("kind", AnswerKind.DECISION_REQUEST.wire());
                node.put("question", request.question().raw());
                texts(node, "options", request.options());
                if (request.continuationKey() != null) {
                    node.put("continuationKey", request.continuationKey());
                }
            }
        }
        return node;
    }

    private static ObjectNode sarif(Answer.Verdict verdict) {
        ObjectNode sarif = NODES.objectNode().put("version", Answer.Verdict.SARIF_VERSION);
        ArrayNode runs = sarif.putArray("runs");
        for (SarifRun run : verdict.runs()) {
            ObjectNode written = runs.addObject();
            written.putObject("tool").putObject("driver").put("name", run.tool().raw());
            ArrayNode results = written.putArray("results");
            for (SarifResult result : run.results()) {
                result(results.addObject(), result);
            }
        }
        return sarif;
    }

    private static void result(ObjectNode node, SarifResult result) {
        optional(node, "ruleId", result.ruleId());
        node.put("level", result.level().wire());
        node.putObject("message").put("text", result.message().raw());
        UntrustedText uri = result.uri();
        Integer startLine = result.startLine();
        if (uri == null && startLine == null) {
            return;
        }
        ObjectNode physical = node.putArray("locations").addObject().putObject("physicalLocation");
        if (uri != null) {
            physical.putObject("artifactLocation").put("uri", uri.raw());
        }
        if (startLine != null) {
            physical.putObject("region").put("startLine", startLine);
        }
    }

    private static void texts(ObjectNode node, String field, List<UntrustedText> texts) {
        if (!texts.isEmpty()) {
            ArrayNode array = node.putArray(field);
            texts.forEach(text -> array.add(text.raw()));
        }
    }

    private static void optional(ObjectNode node, String field, @Nullable UntrustedText value) {
        if (value != null) {
            node.put(field, value.raw());
        }
    }
}
