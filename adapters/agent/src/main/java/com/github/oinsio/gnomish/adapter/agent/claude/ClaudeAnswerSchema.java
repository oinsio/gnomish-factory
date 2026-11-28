package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.adapter.agent.runner.AnswerForm;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import java.util.List;
import java.util.Optional;

/**
 * The JSON Schema of the reply a Claude round answers with in every role it declares (FR3, FR4, FR19
 * of extract-agent-round-runner; design D15): the one schema the CLI is handed as {@code
 * --json-schema} and the one the dialect's answer step judges the reply against ({@link
 * ClaudeAnswer}), so the CLI's validation and the factory's cannot differ.
 *
 * <ul>
 *   <li>{@code transform}: {@code {"outcome": "done" | "decision_request", "question": string,
 *       "options": [string, ...]}} — the work is done, or a decision is asked with its question and
 *       options; written as the contract's {@code decision-request}, whose two-option floor the
 *       runner's read holds, since the subset cannot tie it to the outcome.
 *   <li>{@code verdict}: {@code {"passed": boolean, "findings": [string, ...]}} with every finding
 *       holding a non-blank character; converted into the contract's SARIF verdict.
 *   <li>{@code decision}: {@code {"chosen", "ranking", "rationale"}}, the choice and the ranking held
 *       to exactly the options the request offered; written as the contract's {@code select}.
 * </ul>
 *
 * <p>Every schema is written in the subset both schema families accept: an object root, every
 * object closed ({@code additionalProperties: false}) with all its properties required, and no
 * combinator at the root — OpenAI's strict mode refuses anything else, and Claude's validator
 * accepts it. An {@code effect} request, a role the dialect does not declare, has no schema.
 *
 * <p>Implements FR3, FR4, FR19 of extract-agent-round-runner.
 */
final class ClaudeAnswerSchema {

    /** The {@code transform} reply's outcome when the work is done and nothing is asked. */
    static final String DONE = "done";

    /** The {@code transform} reply's outcome when it asks a human decision. */
    static final String DECISION_REQUEST = "decision_request";

    /**
     * Claude's one answer form (FR19, design D15 part 1): the reply is the CLI's schema-enforced
     * final output in every role, and a {@code transform} round asks a decision through it — the
     * instruction names the fields of the {@code transform} schema, never a file.
     */
    static final AnswerForm FORM = new AnswerForm.SchemaEnforced("""
            === Asking a human ===
            Your final structured reply says how the round ended. If you need a human \
            decision before you can proceed, finish your turn with a final reply whose \
            "outcome" is "decision_request", with your "question" and at least two \
            "options" to choose from. Otherwise its "outcome" is "done", with an empty \
            "question" and no "options".
            """);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ClaudeAnswerSchema() {}

    /**
     * The schema of {@code request}'s reply.
     *
     * @param request the run; never null
     * @return the schema, or empty for a role the dialect does not declare
     */
    static Optional<ObjectNode> of(ExecutorRequest request) {
        return switch (request.role()) {
            case TRANSFORM -> Optional.of(transform());
            case VERDICT -> Optional.of(verdict());
            case DECISION -> Optional.of(decision(request.roleInputs().options()));
            case EFFECT -> Optional.empty();
        };
    }

    private static ObjectNode transform() {
        ObjectNode schema = closed("outcome", "question", "options");
        ObjectNode properties = schema.putObject("properties");
        properties.set("outcome", oneOf(List.of(DONE, DECISION_REQUEST)));
        properties.putObject("question").put("type", "string");
        properties.putObject("options").put("type", "array").putObject("items").put("type", "string");
        return schema;
    }

    private static ObjectNode verdict() {
        ObjectNode schema = closed("passed", "findings");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("passed").put("type", "boolean");
        ObjectNode findings = properties.putObject("findings").put("type", "array");
        findings.putObject("items").put("type", "string").put("pattern", "\\S");
        return schema;
    }

    private static ObjectNode decision(List<String> options) {
        ObjectNode schema = closed("chosen", "ranking", "rationale");
        ObjectNode properties = schema.putObject("properties");
        properties.set("chosen", oneOf(options));
        properties.putObject("ranking").put("type", "array").set("items", oneOf(options));
        properties.putObject("rationale").put("type", "string");
        return schema;
    }

    /** A closed object schema whose properties are exactly {@code required}, all of them required. */
    private static ObjectNode closed(String... required) {
        ObjectNode schema = NODES.objectNode().put("type", "object").put("additionalProperties", false);
        ArrayNode names = schema.putArray("required");
        for (String name : required) {
            names.add(name);
        }
        return schema;
    }

    private static ObjectNode oneOf(List<String> options) {
        ObjectNode node = NODES.objectNode().put("type", "string");
        ArrayNode values = node.putArray("enum");
        options.forEach(values::add);
        return node;
    }
}
