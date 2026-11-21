package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.adapter.agent.claude.FinalTextObject;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The judge reply's conversions (design D5 of add-agent-executor; FR3, FR4 of
 * extract-agent-round-runner). A judge replies {@code {"passed": boolean, "findings": ["...", ...]}}
 * — the shape the judge prompt asks for and {@code ClaudeAnswerSchema} hands the CLI as its {@code
 * --json-schema}.
 *
 * <ul>
 *   <li>{@link #sarif}: the contract's SARIF verdict of a reply the schema accepted, the answer a
 *       {@code verdict} round of the Claude dialect writes. The executor declares no pass (design D5
 *       of migrate-verify-to-executor-contract): a failing reply's findings are {@code error}
 *       results, a failing reply with none is one {@code error} result saying so, and a passing
 *       reply's findings are {@code none} results, which reach no threshold.
 *   <li>{@link #extract}: the engine's {@link Verdict} of a judge round's final message, for the
 *       {@code JudgeRoundExecution} path until task 4.1 of extract-agent-round-runner deletes it:
 *       the object located by {@link FinalTextObject}, then read strictly. Anything short of a
 *       well-formed reply — no object, malformed JSON, a missing or non-boolean {@code passed}, a
 *       {@code findings} entry that is not a non-blank string, a blank message — is {@link
 *       Verdict.CannotVerify}, never a pass (NFR-R1; FR15 of add-sandbox-core; FR7, D5 as amended
 *       of migrate-verify-to-executor-contract), logged at WARN with the raw message (NFR-O2).
 * </ul>
 *
 * <p>Implements FR8, NFR-R1, NFR-O2, D5 of add-agent-executor; FR15 of add-sandbox-core; FR3, FR4 of
 * extract-agent-round-runner.
 */
public final class JudgeVerdictExtractor {

    /** The message of the one result a failing reply that named no finding becomes. */
    public static final String NO_FINDING = "the judge failed the criteria without naming a finding";

    private static final Logger log = LoggerFactory.getLogger(JudgeVerdictExtractor.class);

    /**
     * The SARIF verdict of a judge reply the reply schema accepted.
     *
     * @param reply the reply, accepted by the schema; never null
     * @param tool the name the verdict's one run is attributed to; never blank
     * @return the verdict answer; never null
     */
    public static Answer.Verdict sarif(JsonNode reply, String tool) {
        boolean passed = reply.path("passed").asBoolean();
        SarifLevel level = passed ? SarifLevel.NONE : SarifLevel.ERROR;
        List<SarifResult> results = new ArrayList<>();
        for (JsonNode finding : reply.path("findings")) {
            results.add(result(level, UntrustedText.agent(finding.asText())));
        }
        if (!passed && results.isEmpty()) {
            results.add(result(level, UntrustedText.factory(NO_FINDING)));
        }
        return new Answer.Verdict(List.of(new SarifRun(UntrustedText.factory(tool), results, null)));
    }

    private static SarifResult result(SarifLevel level, UntrustedText message) {
        return new SarifResult(null, level, message, null, null);
    }

    /**
     * Extracts a {@link Verdict} from a judge round's final message (FR8).
     *
     * @param message the agent's final result text, as the round captured it; never null
     * @return {@link Verdict.Pass}, {@link Verdict.Fail}, or {@link
     *     Verdict.CannotVerify} — never null, never throws
     */
    public Verdict extract(UntrustedText message) {
        if (message.isBlank()) {
            return cannotVerify("judge produced no final message", message);
        }
        JsonNode node;
        switch (FinalTextObject.of(message)) {
            case FinalTextObject.Reading.NoObject ignored -> {
                return cannotVerify("no JSON verdict object found in the judge's final message", message);
            }
            case FinalTextObject.Reading.NotJson ignored -> {
                return cannotVerify("judge verdict JSON was malformed", message);
            }
            case FinalTextObject.Reading.Parsed parsed -> node = parsed.object();
        }
        JsonNode passedNode = node.get("passed");
        if (passedNode == null || !passedNode.isBoolean()) {
            return cannotVerify("judge verdict JSON is missing a boolean \"passed\" field", message);
        }

        // One strict read for both outcomes: a pass keeps its findings (D5 as amended of
        // migrate-verify-to-executor-contract), so a malformed array is never a pass either.
        List<Finding> findings = findings(node);
        if (findings == null) {
            return cannotVerify("judge verdict \"findings\" entries must be non-blank strings", message);
        }
        return passedNode.asBoolean() ? new Verdict.Pass(null, findings) : new Verdict.Fail(findings);
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

    /**
     * The degradation exit (NFR-R1, NFR-O2): the WARN line carries the raw message through
     * the findings funnel's log sanitization (FR15 of add-sandbox-core), while the returned
     * {@code details} keep it verbatim — data stays full-fidelity, only the log sink is
     * stripped and capped. The sentence naming the failure is the factory's own — it says what the
     * extraction could not do, not what the judge wrote — so it is minted in the factory's family
     * while {@code details} keeps the judge's, and the two components of a verdict render as one
     * either way (design D3).
     */
    private Verdict.CannotVerify cannotVerify(String reason, UntrustedText rawMessage) {
        log.warn(
                OperatorEvent.JUDGE_VERDICT_UNEXTRACTABLE.head()
                        + "judge verdict could not be extracted ({}); raw final message: {}",
                reason,
                rawMessage.forLog());
        return new Verdict.CannotVerify(UntrustedText.factory(reason), rawMessage);
    }
}
