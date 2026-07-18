package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tolerant verdict-parsing layer over a CLI judge round's final message
 * (design D5): strips markdown code fences if present, locates the first
 * balanced {@code {...}} object in the remaining text, and interprets it as
 * {@code {"passed": boolean, "findings": ["...", ...]}}. Anything short of a
 * well-formed verdict — no JSON object found, malformed JSON, a missing or
 * non-boolean {@code passed} field, or a blank message — becomes {@link
 * Verdict.CannotVerify}; this class never returns {@link Verdict.Pass} unless
 * an explicit {@code passed: true} was read (NFR-R1: the judge is the QC
 * net itself, so its degradation default is inverted from the executor's).
 *
 * <p>The first-JSON-object scan is a simple bracket-matching heuristic, not a
 * full JSON tokenizer: it does not distinguish braces inside string literals
 * from structural braces. This is a deliberate simplification — the input is
 * the agent's own structured output following an explicit instruction (task
 * 7.1's {@code JudgePromptBuilder}), not adversarial or free-form external
 * text, so unbalanced braces inside a finding string are treated as parse
 * trouble (→ {@code CannotVerify}) rather than engineered around.
 *
 * <p>Every degradation path logs the raw final message at WARN (NFR-O2),
 * mirroring {@link DecisionFileReader}'s precedent so protocol
 * non-compliance is diagnosable from logs alone.
 *
 * <p>Implements FR8, NFR-R1, NFR-O2, D5 of add-agent-executor.
 */
public final class JudgeVerdictExtractor {

    private static final Logger log = LoggerFactory.getLogger(JudgeVerdictExtractor.class);

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Extracts a {@link Verdict} from a judge round's final message (FR8).
     *
     * @param finalMessage the agent's final result text, verbatim; never null
     * @return {@link Verdict.Pass}, {@link Verdict.Fail}, or {@link
     *     Verdict.CannotVerify} — never null, never throws
     */
    public Verdict extract(String finalMessage) {
        if (finalMessage.isBlank()) {
            return cannotVerify("judge produced no final message", finalMessage);
        }

        String unfenced = stripFence(finalMessage);
        String jsonObject = firstJsonObject(unfenced);
        if (jsonObject == null) {
            return cannotVerify("no JSON verdict object found in the judge's final message", finalMessage);
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(jsonObject);
        } catch (Exception e) {
            return cannotVerify("judge verdict JSON was malformed", finalMessage);
        }

        JsonNode passedNode = node.get("passed");
        if (passedNode == null || !passedNode.isBoolean()) {
            return cannotVerify("judge verdict JSON is missing a boolean \"passed\" field", finalMessage);
        }

        if (passedNode.asBoolean()) {
            return new Verdict.Pass();
        }
        return new Verdict.Fail(findings(node));
    }

    private List<Finding> findings(JsonNode node) {
        JsonNode findingsNode = node.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }
        return findingsNode
                .valueStream()
                .map(n -> new Finding(n.asText(), null, null))
                .toList();
    }

    private String stripFence(String text) {
        Matcher matcher = FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text;
    }

    /**
     * Bracket-matching scan for the first balanced {@code {...}} span. Known
     * limitation: does not track string literals, so a brace character
     * inside a JSON string value would desynchronize the match — accepted
     * per this class's javadoc, since the input is the agent's own
     * cooperative structured output, not adversarial text.
     */
    private @Nullable String firstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private Verdict.CannotVerify cannotVerify(String reason, String rawMessage) {
        log.warn("judge verdict could not be extracted ({}); raw final message: {}", reason, rawMessage);
        return new Verdict.CannotVerify(reason, rawMessage);
    }
}
