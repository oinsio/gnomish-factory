package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How the SARIF log of a verdict answer becomes an {@link Answer.Verdict}: version {@value
 * Answer.Verdict#SARIF_VERSION}, each run's {@code tool.driver.name} and {@code results[]}, and of
 * each result its {@code ruleId}, {@code level} (absent means warning), {@code message.text} and the
 * first location's {@code physicalLocation}. Every other SARIF property is ignored. A log that
 * lacks what the engine reads, or names a level SARIF does not define, is refused: it is not SARIF
 * the contract can judge, and a verdict that cannot be judged is never a pass.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
final class SarifReader {

    private SarifReader() {}

    static Answer.Verdict verdict(JsonNode sarif, WrittenTexts texts) {
        if (!sarif.isObject()) {
            throw new IllegalArgumentException("the verdict answer has no SARIF log");
        }
        String version = WrittenNodes.text(sarif, "version", "the SARIF log");
        if (!version.equals(Answer.Verdict.SARIF_VERSION)) {
            throw new IllegalArgumentException(
                    "the SARIF log is version '" + version + "'; only " + Answer.Verdict.SARIF_VERSION + " is read");
        }
        List<SarifRun> runs = new ArrayList<>();
        for (JsonNode run : WrittenNodes.array(sarif, "runs", "the SARIF log")) {
            runs.add(run(run, texts));
        }
        return new Answer.Verdict(runs);
    }

    private static SarifRun run(JsonNode run, WrittenTexts texts) {
        UntrustedText tool = texts.text(run.path("tool").path("driver"), "name", "a SARIF run's tool.driver");
        List<SarifResult> results = new ArrayList<>();
        for (JsonNode result : WrittenNodes.array(run, "results", "a SARIF run")) {
            results.add(result(result, texts));
        }
        return new SarifRun(tool, results);
    }

    private static SarifResult result(JsonNode result, WrittenTexts texts) {
        UntrustedText message = texts.text(result.path("message"), "text", "a SARIF result's message");
        String token = WrittenNodes.optionalText(result, "level", "a SARIF result");
        SarifLevel level = token == null
                ? SarifLevel.WARNING
                : SarifLevel.fromWire(token)
                        .orElseThrow(() -> new IllegalArgumentException("unknown SARIF level '" + token + "'"));
        JsonNode physical = result.path("locations").path(0).path("physicalLocation");
        UntrustedText uri = texts.optionalText(physical.path("artifactLocation"), "uri", "a SARIF artifactLocation");
        return new SarifResult(
                texts.optionalText(result, "ruleId", "a SARIF result"),
                level,
                message,
                uri,
                startLine(physical.path("region").path("startLine")));
    }

    private static @Nullable Integer startLine(JsonNode line) {
        if (WrittenNodes.absent(line)) {
            return null;
        }
        if (!line.isInt()) {
            throw new IllegalArgumentException("a SARIF region's startLine is not an integer");
        }
        return line.intValue();
    }
}
