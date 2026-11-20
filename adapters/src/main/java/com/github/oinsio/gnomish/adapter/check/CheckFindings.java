package com.github.oinsio.gnomish.adapter.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.executorcontract.Answer;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.executorcontract.SarifLogReading;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.executorcontract.SarifRun;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a {@code shell} check reported on its {@code GNOMISH_FINDINGS_FILE} channel (FR8 of
 * add-manual-run; FR5 of migrate-verify-to-executor-contract): the file is read once through the
 * environment's capped channel by the executor contract's one {@link ResultFileReader} (FR6 of
 * define-executor-contract), and its content is a SARIF 2.1.0 log — read by the contract's own
 * SARIF reading, {@link Answer#readSarif} — or the legacy {@code {"findings":[…]}}, normalized to
 * {@code error} results (a file holding a {@code findings} member is legacy). The outcome is the
 * reported {@link ShellResult}s, or none, so the caller answers without them (NFR-R2: a broken
 * reporter never degrades the answer to CannotVerify). Absent or blank content degrades silently;
 * malformed content — not JSON, neither shape, a SARIF log the contract refuses, a legacy entry with
 * a blank or missing {@code message} — degrades with a WARN naming the check (FR5 of
 * harden-logging-observability); a file cut at the cap parses as what its first bytes are. More
 * results than one verdict may carry are capped by {@link ResultCap#shell}, never dropped: the file
 * is read on exit 0 too, so a dropped list would pass a check its results fail.
 *
 * <p>The fields of the file arrive from the result reader as carriers and stay carriers in the
 * {@link ShellResult}s, which are the exit where they leave them.
 *
 * <p>Implements FR8, NFR-R2 of add-manual-run; NFR-S3 of add-sandbox-core; FR5 of
 * harden-logging-observability; FR6 of define-executor-contract; FR5 of
 * migrate-verify-to-executor-contract.
 */
final class CheckFindings {

    private static final Logger log = LoggerFactory.getLogger(CheckFindings.class);

    private static final ResultFileReader READER =
            new ResultFileReader(ResultFileReader.FINDINGS_FILE_CAP_BYTES, UntrustedText::subprocess);

    private CheckFindings() {}

    /**
     * Reads the findings channel at {@code path} through {@code environment}'s capped read.
     *
     * @param environment the environment the check ran in; never null
     * @param path the factory-chosen channel path handed to the check; never null
     * @return the read's outcome; never null
     */
    static ReadResult<FindingsFile> read(TaskExecutionEnvironment environment, String path) {
        return READER.readJson(limit -> environment.readFile(path, limit), FindingsFile.class);
    }

    /**
     * The valid results the channel carried, in the order written; empty when it carried none worth
     * using.
     *
     * @param checkIdentity the check whose channel this is, for the degradation warnings (FR5)
     * @param read the channel's read outcome; never null
     * @return the results; never null
     */
    static List<ShellResult> results(String checkIdentity, ReadResult<FindingsFile> read) {
        ReadResult.Content<FindingsFile> content =
                switch (read) {
                    case ReadResult.Absent<FindingsFile> absent -> null;
                    case ReadResult.Truncated<FindingsFile> truncated -> truncated.content();
                    case ReadResult.Content<FindingsFile> whole -> whole;
                };
        return switch (content) {
            case null -> List.of();
            case ReadResult.Parsed<FindingsFile> parsed -> parsed(checkIdentity, parsed.value());
            case ReadResult.Malformed<FindingsFile> malformed -> {
                if (!malformed.content().isBlank()) {
                    warnMalformed(checkIdentity, malformed.reason());
                }
                yield List.of();
            }
        };
    }

    private static List<ShellResult> parsed(String checkIdentity, FindingsFile file) {
        if (file.findings() != null) {
            return ResultCap.shell(legacy(checkIdentity, file.findings()));
        }
        if (file.version() == null && file.runs() == null) {
            log.warn(
                    OperatorEvent.FINDINGS_FILE_MISSING_ARRAY.head()
                            + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: neither a 'findings' array nor"
                            + " a SARIF log; its findings are not used",
                    checkIdentity);
            return List.of();
        }
        ObjectNode sarifLog = JsonNodeFactory.instance.objectNode();
        sarifLog.set("version", file.version());
        sarifLog.set("runs", file.runs());
        return switch (Answer.readSarif(sarifLog, UntrustedText::subprocess)) {
            case SarifLogReading.Parsed parsed -> ResultCap.shell(relayed(parsed.runs()));
            case SarifLogReading.Malformed malformed -> {
                warnMalformed(checkIdentity, malformed.reason());
                yield List.of();
            }
        };
    }

    private static List<ShellResult> relayed(List<SarifRun> runs) {
        List<ShellResult> results = new ArrayList<>();
        for (SarifRun run : runs) {
            for (SarifResult result : run.results()) {
                results.add(new ShellResult(result, null));
            }
        }
        return results;
    }

    private static List<ShellResult> legacy(String checkIdentity, List<@Nullable FindingWire> entries) {
        List<ShellResult> results = new ArrayList<>(entries.size());
        for (FindingWire entry : entries) {
            if (entry == null || entry.message() == null || entry.message().isBlank()) {
                log.warn(
                        OperatorEvent.FINDINGS_FILE_BLANK_ENTRY.head()
                                + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: an entry has a blank/missing"
                                + " 'message'; its findings are not used",
                        checkIdentity);
                return List.of();
            }
            results.add(ShellResult.legacy(entry.message(), entry.location(), entry.details()));
        }
        return results;
    }

    private static void warnMalformed(String checkIdentity, UntrustedText reason) {
        log.warn(
                OperatorEvent.FINDINGS_FILE_MALFORMED.head()
                        + "GNOMISH_FINDINGS_FILE of check '{}' is malformed ({}); its findings are not used",
                checkIdentity,
                reason.forLog());
    }

    /**
     * The channel's root object: the legacy {@code {"findings":[…]}} (FR8), or a SARIF log's {@code
     * version} and {@code runs}, which the contract's SARIF reading judges.
     *
     * @param findings the legacy entries, or {@code null} when the key is absent
     * @param version a SARIF log's version, unread, or {@code null} — a tree like {@code runs}, so the
     *     contract's SARIF reading judges its type too
     * @param runs a SARIF log's runs, unread, or {@code null}
     */
    record FindingsFile(
            @Nullable List<@Nullable FindingWire> findings,
            @Nullable JsonNode version,
            @Nullable JsonNode runs) {}

    /**
     * One legacy entry, before validation (FR8): {@code message} is required non-blank, {@code
     * location} and {@code details} are optional.
     */
    record FindingWire(
            @Nullable UntrustedText message,
            @Nullable UntrustedText location,
            @Nullable UntrustedText details) {}
}
