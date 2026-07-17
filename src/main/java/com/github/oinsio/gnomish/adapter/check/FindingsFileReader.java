package com.github.oinsio.gnomish.adapter.check;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.Finding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and parses the {@code GNOMISH_FINDINGS_FILE} wire format ({@code {"findings":[…]}},
 * FR8) into validated {@link Finding}s, degrading to {@code null} on any absent, empty, or
 * malformed input (invalid JSON, missing {@code findings} array, or an entry with a
 * blank/missing {@code message}) — logging a warning naming the problem — so the caller can
 * fall back to a synthetic finding of its own (NFR-R2: the exit-code verdict always stands,
 * never degraded to CannotVerify by a broken reporter).
 *
 * <p>Implements FR8, NFR-R2 of add-manual-run.
 */
final class FindingsFileReader {

    private static final Logger log = LoggerFactory.getLogger(FindingsFileReader.class);

    private static final ObjectMapper FINDINGS_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FindingsFileReader() {}

    /**
     * Logs a warning if {@code findingsFile} has content despite the command exiting 0 (Pass):
     * the content is otherwise ignored per FR8.
     */
    static void warnIfIgnoredOnPass(@Nullable Path findingsFile) {
        String content = readFileQuietly(findingsFile);
        if (!content.isBlank()) {
            log.warn("GNOMISH_FINDINGS_FILE has content but the command exited 0 (Pass); ignoring it per FR8");
        }
    }

    /**
     * Reads and parses {@code findingsFile} into a validated, non-empty list of {@link
     * Finding}s, or {@code null} if the file is absent, empty, or malformed in any way.
     */
    @Nullable
    static List<Finding> read(@Nullable Path findingsFile) {
        String content = readFileQuietly(findingsFile);
        if (content.isBlank()) {
            return null;
        }
        try {
            FindingsFile wire = FINDINGS_MAPPER.readValue(content, FindingsFile.class);
            if (wire.findings() == null) {
                log.warn("GNOMISH_FINDINGS_FILE is malformed: missing 'findings' array; using synthetic finding");
                return null;
            }
            List<Finding> findings = new ArrayList<>();
            for (FindingWire entry : wire.findings()) {
                if (entry.message() == null || entry.message().isBlank()) {
                    log.warn("GNOMISH_FINDINGS_FILE is malformed: an entry has a blank/missing 'message'; using"
                            + " synthetic finding");
                    return null;
                }
                findings.add(new Finding(entry.message(), entry.location(), entry.details()));
            }
            return findings;
        } catch (IOException e) {
            log.warn("GNOMISH_FINDINGS_FILE is malformed: {}; using synthetic finding", e.toString());
            return null;
        }
    }

    /**
     * Reads {@code findingsFile}'s content, or {@code ""} for every "nothing to read" case — a
     * {@code null} path, a missing/empty file, or a read failure — rather than {@code null}:
     * both callers ({@link #warnIfIgnoredOnPass}, {@link #read}) only ever test {@code
     * content.isBlank()}, so a distinct {@code null} case would be an unobservable, untestable
     * duplicate of the empty-string case. Collapsing "no content" onto one value keeps every
     * branch here meaningfully mutation-testable (no equivalent mutants to exclude).
     */
    private static String readFileQuietly(@Nullable Path findingsFile) {
        if (findingsFile == null) {
            return "";
        }
        try {
            if (!Files.exists(findingsFile) || Files.size(findingsFile) == 0) {
                return "";
            }
            return Files.readString(findingsFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The {@code GNOMISH_FINDINGS_FILE} wire format's root object: {@code {"findings":[…]}}
     * (FR8).
     *
     * @param findings the reported findings, or {@code null} if the key is absent (treated as
     *     malformed)
     */
    record FindingsFile(@Nullable List<FindingWire> findings) {}

    /**
     * One entry of the {@code GNOMISH_FINDINGS_FILE} wire format, mirroring {@link Finding}'s
     * fields before validation (FR8): {@code message} is required non-blank, {@code
     * location}/{@code details} are optional.
     *
     * @param message what is wrong; a blank/missing value makes the entry malformed
     * @param location an optional locator, or {@code null} if none
     * @param details optional extra detail, or {@code null} if none
     */
    record FindingWire(
            @Nullable String message,
            @Nullable String location,
            @Nullable String details) {}
}
