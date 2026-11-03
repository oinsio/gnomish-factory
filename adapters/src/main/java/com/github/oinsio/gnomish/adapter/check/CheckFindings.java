package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a command check reported on its {@code GNOMISH_FINDINGS_FILE} channel ({@code
 * {"findings":[…]}}, FR8 of add-manual-run): the file is read through the environment's capped
 * channel by the executor contract's one {@link ResultFileReader} (FR6 of define-executor-contract),
 * capped at {@link ResultFileReader#FINDINGS_FILE_CAP_BYTES} with every text field minted as
 * subprocess text, and the outcome becomes validated {@link Finding}s — or {@code null}, so the
 * caller falls back to its synthetic finding (NFR-R2: the exit-code verdict always stands, never
 * degraded to CannotVerify by a broken reporter). Absent or blank content degrades silently;
 * malformed content — not JSON, a missing {@code findings} array, an entry with a blank or
 * missing {@code message} — degrades with a WARN naming the check (FR5 of
 * harden-logging-observability); a file cut at the cap parses as what its first bytes are.
 *
 * <p>An {@link UntrustedExit}: a findings-funnel entry like {@code JudgeVerdictExtractor}. The
 * domain {@link Finding} holds plain text, so the carrier's raw text is what it receives; the
 * funnel's own sanitizer is the control at that boundary.
 *
 * <p>Implements FR8, NFR-R2 of add-manual-run; NFR-S3 of add-sandbox-core; FR5 of
 * harden-logging-observability; FR6 of define-executor-contract.
 */
@UntrustedExit
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
     * Notes, at DEBUG, that the channel carried content although the command exited 0 (Pass): the
     * content is ignored per FR8. DEBUG, not WARN (FR12 of harden-logging-observability): writing
     * findings unconditionally is the check author's habit, not a degradation of this run.
     */
    static void noteIgnoredOnPass(String checkIdentity, ReadResult<FindingsFile> read) {
        if (hasContent(read)) {
            log.debug(
                    "GNOMISH_FINDINGS_FILE of check '{}' has content but the command exited 0 (Pass);"
                            + " ignoring it per FR8",
                    checkIdentity);
        }
    }

    /**
     * The validated, non-empty findings the channel carried, or {@code null} when it carried none
     * worth using.
     *
     * @param checkIdentity the check whose channel this is, for the degradation warnings (FR5)
     * @param read the channel's read outcome; never null
     */
    static @Nullable List<Finding> findings(String checkIdentity, ReadResult<FindingsFile> read) {
        ReadResult.Content<FindingsFile> content =
                switch (read) {
                    case ReadResult.Absent<FindingsFile> absent -> null;
                    case ReadResult.Truncated<FindingsFile> truncated -> truncated.content();
                    case ReadResult.Content<FindingsFile> whole -> whole;
                };
        return switch (content) {
            case null -> null;
            case ReadResult.Parsed<FindingsFile> parsed -> validated(checkIdentity, parsed.value());
            case ReadResult.Malformed<FindingsFile> malformed -> malformed(checkIdentity, malformed);
        };
    }

    private static @Nullable List<Finding> malformed(String checkIdentity, ReadResult.Malformed<FindingsFile> read) {
        if (read.content().isBlank()) {
            return null;
        }
        log.warn(
                OperatorEvent.FINDINGS_FILE_MALFORMED.head()
                        + "GNOMISH_FINDINGS_FILE of check '{}' is malformed ({}); using synthetic finding",
                checkIdentity,
                read.reason().forLog());
        return null;
    }

    private static @Nullable List<Finding> validated(String checkIdentity, FindingsFile file) {
        if (file.findings() == null) {
            log.warn(
                    OperatorEvent.FINDINGS_FILE_MISSING_ARRAY.head()
                            + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: missing 'findings' array;"
                            + " using synthetic finding",
                    checkIdentity);
            return null;
        }
        List<Finding> findings = new ArrayList<>();
        for (FindingWire entry : file.findings()) {
            if (entry == null || entry.message() == null || entry.message().isBlank()) {
                log.warn(
                        OperatorEvent.FINDINGS_FILE_BLANK_ENTRY.head()
                                + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: an entry has a blank/missing"
                                + " 'message'; using synthetic finding",
                        checkIdentity);
                return null;
            }
            findings.add(new Finding(entry.message().raw(), rawOrNull(entry.location()), rawOrNull(entry.details())));
        }
        return findings;
    }

    private static boolean hasContent(ReadResult<FindingsFile> read) {
        return switch (read) {
            case ReadResult.Absent<FindingsFile> absent -> false;
            case ReadResult.Truncated<FindingsFile> truncated -> true;
            case ReadResult.Parsed<FindingsFile> parsed -> true;
            case ReadResult.Malformed<FindingsFile> malformed ->
                !malformed.content().isBlank();
        };
    }

    private static @Nullable String rawOrNull(@Nullable UntrustedText text) {
        return text == null ? null : text.raw();
    }

    /**
     * The channel's root object, {@code {"findings":[…]}} (FR8).
     *
     * @param findings the reported entries, or {@code null} when the key is absent (malformed)
     */
    record FindingsFile(@Nullable List<@Nullable FindingWire> findings) {}

    /**
     * One entry, before validation (FR8): {@code message} is required non-blank, {@code location}
     * and {@code details} are optional.
     */
    record FindingWire(
            @Nullable UntrustedText message,
            @Nullable UntrustedText location,
            @Nullable UntrustedText details) {}
}
