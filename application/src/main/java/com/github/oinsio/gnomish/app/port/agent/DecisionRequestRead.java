package com.github.oinsio.gnomish.app.port.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.github.oinsio.gnomish.executorcontract.ReadResult;
import com.github.oinsio.gnomish.executorcontract.ResultFileReader;
import com.github.oinsio.gnomish.executorcontract.ResultSource;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one tolerant read of a round's decision file (FR3 of add-agent-executor; FR6 of
 * define-executor-contract), shared by every place the file is read: the host temp-directory
 * transport, the in-branch file a box writes, and the request a pending verification recovers
 * from a snapshot's tree. Each hands its medium over as a {@link ResultSource}; the bytes go
 * through the executor contract's one {@link ResultFileReader}, capped at {@link
 * ResultFileReader#DECISION_FILE_CAP_BYTES} and minted as agent text, so the cap and the fallbacks
 * are one rule for every medium rather than a copy per medium. Four outcomes:
 *
 * <ul>
 *   <li>no file → {@link Optional#empty()}: the agent asked nothing, the round completed;
 *   <li>a JSON {@code {"question": "...", "options": ["...", ...]}} → that request;
 *   <li>unparseable content — not JSON, a missing or {@code null} {@code question}, a {@code null}
 *       option → the raw text as the question, with no options and a WARN carrying a capped,
 *       neutralized excerpt; a file longer than the cap is read up to the cap and its text ends
 *       with the reader's truncation marker;
 *   <li>an empty or blank file → {@link #FALLBACK_QUESTION}, with no options and a WARN.
 * </ul>
 *
 * <p>The shape check is strict on purpose: this is the agent's own structured output, so a shape
 * mismatch means the agent did not follow the protocol, and it is treated as parse trouble rather
 * than silently defaulted. Unknown fields are ignored, as the contract's binding ignores them.
 *
 * <p>Implements FR3, NFR-O2 of add-agent-executor; FR6 of define-executor-contract.
 */
public final class DecisionRequestRead {

    private static final Logger log = LoggerFactory.getLogger(DecisionRequestRead.class);

    static final String FALLBACK_QUESTION = "agent requested a decision but wrote no content";

    /**
     * How much of an unparseable decision file reaches the log line (FR6 of
     * harden-logging-observability): enough to recognize what the agent wrote instead of JSON, not
     * a copy of the file — the file's real consumer is the request the same read returns.
     */
    private static final int RAW_CONTENT_CAP_CHARS = 500;

    private static final ResultFileReader READER =
            new ResultFileReader(ResultFileReader.DECISION_FILE_CAP_BYTES, UntrustedText::agent);

    private DecisionRequestRead() {}

    /**
     * Reads the decision file behind {@code source}.
     *
     * @param source the round's decision file on its own medium; never null
     * @return the request, or empty when the agent wrote no file; never null
     */
    public static Optional<RoundDecisionRequest> read(ResultSource source) {
        return switch (READER.readJson(source, Payload.class)) {
            case ReadResult.Absent<Payload> absent -> Optional.empty();
            case ReadResult.Truncated<Payload> truncated -> Optional.of(request(truncated.content()));
            case ReadResult.Content<Payload> content -> Optional.of(request(content));
        };
    }

    private static RoundDecisionRequest request(ReadResult.Content<Payload> content) {
        return switch (content) {
            case ReadResult.Parsed<Payload> parsed -> request(parsed.value());
            case ReadResult.Malformed<Payload> malformed -> fallback(malformed.content());
        };
    }

    private static RoundDecisionRequest request(Payload payload) {
        List<UntrustedText> options = payload.options();
        return new RoundDecisionRequest(payload.question(), options == null ? List.of() : options);
    }

    private static RoundDecisionRequest fallback(UntrustedText raw) {
        if (raw.isBlank()) {
            log.warn(OperatorEvent.DECISION_FILE_EMPTY.head()
                    + "decision file was empty; falling back to a stand-in question");
            return new RoundDecisionRequest(UntrustedText.factory(FALLBACK_QUESTION), List.of());
        }
        // FR6 of harden-logging-observability: the content is the agent's, so the line carries a
        // capped, neutralized excerpt, while the request keeps the text whole for the human.
        log.warn(
                OperatorEvent.DECISION_FILE_NOT_JSON.head()
                        + "decision file was not valid decision JSON; using raw content as the question: {}",
                raw.excerpt(RAW_CONTENT_CAP_CHARS));
        return new RoundDecisionRequest(raw, List.of());
    }

    /**
     * The {@code {"question": ..., "options": [...]}} wire shape. A missing or {@code null}
     * question and a {@code null} option fail the binding, so the read reports them as malformed;
     * an absent or {@code null} options list is no options.
     */
    private record Payload(
            @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
            UntrustedText question,

            @JsonSetter(contentNulls = Nulls.FAIL) @Nullable List<UntrustedText> options) {}
}
