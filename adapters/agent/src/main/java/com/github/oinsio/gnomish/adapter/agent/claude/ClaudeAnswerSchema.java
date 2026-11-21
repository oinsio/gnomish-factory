package com.github.oinsio.gnomish.adapter.agent.claude;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.Role;
import java.util.List;
import java.util.Optional;

/**
 * The JSON Schema of the reply a {@code verdict} or {@code decision} round answers with (FR3, FR4 of
 * extract-agent-round-runner): the one schema the CLI is handed as {@code --json-schema} and the
 * one the dialect's answer step judges the reply against ({@link ClaudeAnswer}), so the CLI's
 * validation and the factory's cannot differ.
 *
 * <ul>
 *   <li>{@code verdict}: the judge reply the judge prompt asks for, {@code {"passed": boolean,
 *       "findings": [string, ...]}} with every finding holding a non-blank character; converted
 *       into the contract's SARIF verdict by {@code JudgeVerdictExtractor}.
 *   <li>{@code decision}: {@code {"chosen", "ranking"?, "rationale"?}}, the choice and the ranking
 *       held to exactly the options the request offered; written as the contract's {@code select}.
 * </ul>
 *
 * <p>Other roles answer with no schema: a {@code transform} round's answer, a decision request
 * included, is the file the agent writes itself.
 *
 * <p>Implements FR3, FR4 of extract-agent-round-runner.
 */
final class ClaudeAnswerSchema {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ClaudeAnswerSchema() {}

    /**
     * The schema of {@code request}'s reply.
     *
     * @param request the run; never null
     * @return the schema, or empty for a role that answers without one
     */
    static Optional<ObjectNode> of(ExecutorRequest request) {
        if (request.role() == Role.VERDICT) {
            return Optional.of(verdict());
        }
        if (request.role() == Role.DECISION) {
            return Optional.of(decision(request.roleInputs().options()));
        }
        return Optional.empty();
    }

    private static ObjectNode verdict() {
        ObjectNode schema = object("passed");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("passed").put("type", "boolean");
        ObjectNode findings = properties.putObject("findings").put("type", "array");
        findings.putObject("items").put("type", "string").put("pattern", "\\S");
        return schema;
    }

    private static ObjectNode decision(List<String> options) {
        ObjectNode schema = object("chosen");
        ObjectNode properties = schema.putObject("properties");
        properties.set("chosen", oneOf(options));
        properties.putObject("ranking").put("type", "array").set("items", oneOf(options));
        properties.putObject("rationale").put("type", "string");
        return schema;
    }

    private static ObjectNode object(String required) {
        ObjectNode schema = NODES.objectNode().put("type", "object");
        schema.putArray("required").add(required);
        return schema;
    }

    private static ObjectNode oneOf(List<String> options) {
        ObjectNode node = NODES.objectNode();
        ArrayNode values = node.putArray("enum");
        options.forEach(values::add);
        return node;
    }
}
