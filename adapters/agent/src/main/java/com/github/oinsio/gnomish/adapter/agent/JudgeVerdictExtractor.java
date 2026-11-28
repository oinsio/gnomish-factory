package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.SarifLevel;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;

/**
 * The judge reply's conversion into the contract's SARIF verdict (design D5 of add-agent-executor;
 * FR3, FR4 of extract-agent-round-runner). A judge replies {@code {"passed": boolean, "findings":
 * ["...", ...]}} — the shape the judge prompt asks for and {@code ClaudeAnswerSchema} hands the CLI
 * as its {@code --json-schema}; a reply the schema refuses never reaches this class, it is a
 * malformed answer of the dialect.
 *
 * <p>The executor declares no pass (design D5 of migrate-verify-to-executor-contract): a failing
 * reply's findings are {@code error} results, a failing reply with none is one {@code error} result
 * saying so, and a passing reply's findings are {@code none} results, which reach no threshold. The
 * verdict itself is derived by the engine's one derivation.
 *
 * <p>Implements FR8, D5 of add-agent-executor; FR3, FR4 of extract-agent-round-runner.
 */
public final class JudgeVerdictExtractor {

    /** The message of the one result a failing reply that named no finding becomes. */
    public static final String NO_FINDING = "the judge failed the criteria without naming a finding";

    private JudgeVerdictExtractor() {}

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
}
