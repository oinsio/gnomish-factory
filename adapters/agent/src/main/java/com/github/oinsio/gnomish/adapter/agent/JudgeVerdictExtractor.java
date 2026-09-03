package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.findings.FindingsSanitizer;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.util.ArrayList;
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
 * non-boolean {@code passed} field, a {@code findings} entry that is not a
 * non-blank string, or a blank message — becomes {@link
 * Verdict.CannotVerify}; this class never returns {@link Verdict.Pass} unless
 * an explicit {@code passed: true} was read (NFR-R1: the judge is the QC
 * net itself, so its degradation default is inverted from the executor's).
 * This is the strict verdict schema of the findings funnel: a judge reply
 * that fails it classifies as an infrastructure failure of the check, never
 * as a quality verdict (FR15 of add-sandbox-core).
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
 * <p>Implements FR8, NFR-R1, NFR-O2, D5 of add-agent-executor; FR15 of
 * add-sandbox-core.
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
        List<Finding> findings = findings(node);
        if (findings == null) {
            return cannotVerify("judge verdict \"findings\" entries must be non-blank strings", finalMessage);
        }
        return new Verdict.Fail(findings);
    }

    /**
     * Reads the optional {@code findings} array under the strict schema (FR15 of
     * add-sandbox-core): an absent or non-array node means no findings; an array with any
     * non-string or blank entry means the verdict as a whole is malformed — {@code null}
     * here, {@code CannotVerify} for the caller — never a partially parsed quality verdict.
     */
    private @Nullable List<Finding> findings(JsonNode node) {
        JsonNode findingsNode = node.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode entry : findingsNode) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                return null;
            }
            findings.add(new Finding(entry.asText(), null, null));
        }
        return List.copyOf(findings);
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

    /**
     * The degradation exit (NFR-R1, NFR-O2): the WARN line carries the raw message through
     * the findings funnel's log sanitization (FR15 of add-sandbox-core), while the returned
     * {@code details} keep it verbatim — data stays full-fidelity, only the log sink is
     * stripped and capped.
     */
    private Verdict.CannotVerify cannotVerify(String reason, String rawMessage) {
        log.warn(
                OperatorEvent.JUDGE_VERDICT_UNEXTRACTABLE.head()
                        + "judge verdict could not be extracted ({}); raw final message: {}",
                reason,
                FindingsSanitizer.forLog(rawMessage));
        return new Verdict.CannotVerify(reason, rawMessage);
    }
}
