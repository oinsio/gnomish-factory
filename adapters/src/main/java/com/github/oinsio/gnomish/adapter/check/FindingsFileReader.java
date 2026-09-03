package com.github.oinsio.gnomish.adapter.check;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the {@code GNOMISH_FINDINGS_FILE} wire format ({@code {"findings":[…]}},
 * FR8) into validated {@link Finding}s, degrading to {@code null} on any absent, empty, or
 * malformed input (invalid JSON, missing {@code findings} array, or an entry with a
 * blank/missing {@code message}) — logging a warning naming the problem — so the caller can
 * fall back to a synthetic finding of its own (NFR-R2: the exit-code verdict always stands,
 * never degraded to CannotVerify by a broken reporter). The input is the size-capped byte
 * content read back through the task environment's {@code readFile} (FR1, NFR-S3 of
 * add-sandbox-core) — this class never touches the filesystem.
 *
 * <p>Every line names the check whose channel was read: one run verifies through many command
 * checks, so an unattributed "the findings file is malformed" sends the operator hunting through
 * the manifest for which one wrote it (FR5 of harden-logging-observability).
 *
 * <p>Implements FR8, NFR-R2 of add-manual-run; NFR-S3 of add-sandbox-core; FR5 of
 * harden-logging-observability.
 */
final class FindingsFileReader {

    private static final Logger log = LoggerFactory.getLogger(FindingsFileReader.class);

    private static final ObjectMapper FINDINGS_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FindingsFileReader() {}

    /**
     * Notes, at DEBUG, that the findings channel carried content despite the command exiting 0
     * (Pass): the content is otherwise ignored per FR8.
     *
     * <p>DEBUG, not WARN (FR12 of harden-logging-observability): a check that writes its findings
     * unconditionally is a habit of the check's author, not a degradation of this run — nothing
     * about the verdict is worse for it, and the operator has nothing to act on. It stays on the
     * record because it is exactly what someone debugging "why were my findings ignored" needs.
     */
    static void noteIgnoredOnPass(String checkIdentity, byte @Nullable [] content) {
        if (!asText(content).isBlank()) {
            log.debug(
                    "GNOMISH_FINDINGS_FILE of check '{}' has content but the command exited 0 (Pass);"
                            + " ignoring it per FR8",
                    checkIdentity);
        }
    }

    /**
     * Parses {@code content} into a validated, non-empty list of {@link Finding}s, or {@code
     * null} if the content is absent, empty, or malformed in any way.
     *
     * @param checkIdentity the check whose channel this is, for the degradation warnings (FR5)
     * @param content the size-capped bytes read back from the channel, or {@code null} if absent
     */
    @Nullable
    static List<Finding> read(String checkIdentity, byte @Nullable [] content) {
        String text = asText(content);
        if (text.isBlank()) {
            return null;
        }
        try {
            FindingsFile wire = FINDINGS_MAPPER.readValue(text, FindingsFile.class);
            if (wire.findings() == null) {
                log.warn(
                        OperatorEvent.FINDINGS_FILE_MISSING_ARRAY.head()
                                + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: missing 'findings' array;"
                                + " using synthetic finding",
                        checkIdentity);
                return null;
            }
            List<Finding> findings = new ArrayList<>();
            for (FindingWire entry : wire.findings()) {
                if (entry.message() == null || entry.message().isBlank()) {
                    log.warn(
                            OperatorEvent.FINDINGS_FILE_BLANK_ENTRY.head()
                                    + "GNOMISH_FINDINGS_FILE of check '{}' is malformed: an entry has a blank/missing"
                                    + " 'message'; using synthetic finding",
                            checkIdentity);
                    return null;
                }
                findings.add(new Finding(entry.message(), entry.location(), entry.details()));
            }
            return findings;
        } catch (IOException e) {
            log.warn(
                    OperatorEvent.FINDINGS_FILE_MALFORMED.head()
                            + "GNOMISH_FINDINGS_FILE of check '{}' is malformed; using synthetic finding",
                    checkIdentity,
                    e);
            return null;
        }
    }

    /**
     * Decodes {@code content}, or {@code ""} for the "nothing to read" case (an absent channel
     * file reads back as {@code null}) rather than {@code null}: both callers only ever test
     * {@code text.isBlank()}, so a distinct {@code null} case would be an unobservable,
     * untestable duplicate of the empty-string case.
     */
    private static String asText(byte @Nullable [] content) {
        return content == null ? "" : new String(content, StandardCharsets.UTF_8);
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
