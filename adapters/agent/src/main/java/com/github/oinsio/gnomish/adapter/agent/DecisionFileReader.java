package com.github.oinsio.gnomish.adapter.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.logtext.LogText;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tolerant parsing layer over {@link DecisionFileTransport.Handle#readAndClose()}'s
 * raw content (design D1): interprets what the transport read, never touches the
 * filesystem itself. Three outcomes:
 *
 * <ul>
 *   <li>content absent (the agent never wrote the file) → {@link Optional#empty()},
 *       which the caller (task 6.5) maps to {@code Completed};
 *   <li>content present and valid JSON {@code {"question": "...", "options":
 *       ["...", ...]}} → a {@link Decision} carrying the extracted question and
 *       options;
 *   <li>content present but unparseable, or blank/empty — collectively "parse
 *       trouble" — → a {@link Decision} with empty options. Garbage content becomes
 *       the question verbatim (nothing is lost); an empty/blank file falls back to
 *       {@link #FALLBACK_QUESTION}, since there is no raw text worth surfacing as a
 *       question. Both cases log the raw content at WARN (NFR-O2) — an empty file is
 *       parse trouble in the same sense as garbage (no valid decision-file JSON was
 *       produced), so it gets the same diagnosability treatment; the two log lines
 *       differ only in whether they carry any raw text.
 * </ul>
 *
 * <p>The strict shape check ({@code FAIL_ON_UNKNOWN_PROPERTIES} left off, but a
 * missing {@code question} field still fails) is intentional: this is the agent's
 * own structured output, not a semi-documented external wire format like
 * stream-json — a shape mismatch signals the agent did not follow the decision-file
 * protocol, and should be treated as parse trouble rather than silently defaulted.
 *
 * <p>Implements FR3, NFR-O2, D1 of add-agent-executor.
 */
public final class DecisionFileReader {

    private static final Logger log = LoggerFactory.getLogger(DecisionFileReader.class);

    private static final String FALLBACK_QUESTION = "agent requested a decision but wrote no content";

    /**
     * How much of an unparseable decision file reaches the log line (FR6). Well below {@link
     * LogText#DEFAULT_CAP_CHARS}: the point of the line is to show enough of the content to
     * recognize what the agent wrote instead of JSON, not to reproduce the file — the file's real
     * consumer is the {@link Decision} the same method returns, uncapped.
     */
    private static final int RAW_CONTENT_CAP_CHARS = 500;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Interprets the transport's raw content into a decision signal (FR3).
     *
     * @param rawContent the transport's {@code readAndClose()} result: {@link
     *     Optional#empty()} when the agent never wrote the decision file, otherwise
     *     the file's raw text; never null
     * @return {@link Optional#empty()} when {@code rawContent} is absent (no
     *     decision); otherwise a present {@link Decision} — either parsed from valid
     *     JSON, or a tolerant fallback for parse trouble; never null
     */
    public Optional<Decision> read(Optional<String> rawContent) {
        if (rawContent.isEmpty()) {
            return Optional.empty();
        }
        String raw = rawContent.get();
        if (raw.isBlank()) {
            log.warn("decision file was empty; falling back to a stand-in question");
            return Optional.of(new Decision(FALLBACK_QUESTION, List.of()));
        }
        try {
            Payload payload = MAPPER.readValue(raw, Payload.class);
            if (payload.question() == null) {
                throw new IllegalArgumentException("decision-file JSON is missing the \"question\" field");
            }
            List<String> options = payload.options() == null ? List.of() : List.copyOf(payload.options());
            return Optional.of(new Decision(payload.question(), options));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // FR6: the decision file is written by the agent, so its raw content is attacker-influenced
            // — capped and neutralized before it reaches the line, while the Decision itself still
            // carries the content verbatim for the human who answers the question.
            log.warn(
                    "decision file was not valid decision JSON; using raw content as the question: {}",
                    LogText.forLog(raw, RAW_CONTENT_CAP_CHARS));
            return Optional.of(new Decision(raw, List.of()));
        }
    }

    /**
     * The parsed (question, options) pair lifted from the decision file — a minimal
     * shape for the caller (task 6.5) to fold into {@code
     * ExecutionResult.DecisionNeeded} alongside usage/trace this class does not have.
     *
     * @param question the question text; never null
     * @param options the offered options, in file order; never null, possibly empty
     */
    public record Decision(String question, List<String> options) {}

    /**
     * The strict {@code {"question": ..., "options": [...]}} wire shape. Both components are
     * declared {@link Nullable}: Jackson populates them from whatever JSON the agent wrote, so a
     * missing field lands as {@code null} here regardless of the record's own type — the {@code
     * null} checks below are the guard against exactly that, not dead code.
     */
    private record Payload(
            @Nullable String question, @Nullable List<String> options) {}
}
