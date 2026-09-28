package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * The Jackson codec over {@link GithubMarkerFields}, the wire form of the hidden
 * structural JSON {@link GithubMarker} renders into and parses back out of a
 * comment body.
 *
 * <p>Split out of {@link GithubMarker} when the content identity joined the
 * wire shape: the codec and the comment-body encoding are two things, and
 * keeping both in one class pushed it past the file-size rule. The data then
 * left for {@link GithubMarkerFields} (design D12 of add-parameter-count-gate),
 * following the {@code TaskJsonDto} precedent in {@code adapters/git}: a
 * components-only Jackson record mutates cleanly under PIT, while explicit
 * methods inside a record crash its minion (hcoles/pitest#1285) — so the
 * behaviour stays here, in an ordinary class, inside the mutation gate.
 *
 * <p>Implements FR7 of add-tracker-port, FR11, FR13 of harden-task-branch-contract,
 * FR6 of add-parameter-count-gate.
 */
final class GithubMarkerJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Renders the fields as the one-line JSON of a structural marker.
     *
     * @param fields the marker's wire fields
     * @return the serialized JSON object
     */
    static String serialize(GithubMarkerFields fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize gnomish structural marker JSON", e);
        }
    }

    /**
     * Parses one structural-marker JSON object, returning empty — never
     * throwing — on malformed input, so {@link GithubMarker#parse} can treat
     * an unrecognizable body as "not a factory marker".
     *
     * @param json the JSON object text found inside the hidden HTML comment
     * @return the parsed fields, or empty if {@code json} is not well-formed
     */
    static Optional<GithubMarkerFields> deserialize(String json) {
        try {
            return Optional.of(MAPPER.readValue(json, GithubMarkerFields.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * The content identity the fields carry, or empty for a marker written
     * before FR11 (both parts absent) — a half-present pair is treated as
     * absent rather than half-built, since neither part identifies a comment
     * on its own.
     *
     * @param fields the marker's wire fields
     * @return the content identity, or empty when the marker carries none
     */
    static Optional<GithubCommentIdentity> identity(GithubMarkerFields fields) {
        String task = fields.task();
        String intent = fields.intent();
        if (task == null || intent == null) {
            return Optional.empty();
        }
        return Optional.of(new GithubCommentIdentity(task, intent));
    }

    private GithubMarkerJson() {}
}
