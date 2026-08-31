package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The wire form of the hidden structural JSON {@link GithubMarker} renders
 * into and parses back out of a comment body, and the Jackson codec over it.
 * Field declaration order fixes wire-key order, and every optional field is
 * omitted entirely when {@code null} (via {@link JsonInclude}), so a marker
 * carrying none of them keeps the original four-field
 * {@code kind}/{@code instance}/{@code at}/{@code version} shape and a marker
 * written before FR11 still parses.
 *
 * <p>Split out of {@link GithubMarker} when the content identity joined the
 * wire shape: the codec and the comment-body encoding are two things, and
 * keeping both in one class pushed it past the file-size rule.
 *
 * <p>A plain final class rather than a record, following the precedent of
 * {@code FilesExistCheckRunner}: PIT's Gregor engine hot-swaps bytecode into
 * the already-loaded class, and the JVM refuses a redefinition that changes a
 * record's {@code Record}/{@code NestHost} attributes (hcoles/pitest#1285, a
 * JVMTI restriction with no PIT-side workaround), which turned the two
 * behaviour-bearing methods below into RUN_ERRORs — a crashed minion, not a
 * mutation result. Carrying the data in an ordinary class keeps them inside a
 * real mutation gate instead of documenting an exemption from it.
 *
 * <p>Implements FR7 of add-tracker-port, FR11, FR13 of harden-task-branch-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
final class GithubMarkerJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final @Nullable String kind;
    private final @Nullable String instance;
    private final @Nullable String at;
    private final int version;
    private final @Nullable String reason;
    private final @Nullable String task;
    private final @Nullable String intent;
    private final @Nullable Long epoch;

    /**
     * @param kind the marker kind's wire value
     * @param instance the identifier of the instance that posted the marker
     * @param at the marker's creation instant, ISO-8601
     * @param version the structural-JSON format version
     * @param reason the park reason's wire value, or {@code null} for every other kind
     * @param task the content identity's task part, or {@code null} on a pre-FR11 marker
     * @param intent the content identity's intent part, or {@code null} on a pre-FR11 marker
     * @param epoch the tenure's claim epoch this write belongs to, or {@code null} when the
     *     writer holds no tenure — and on a {@code claim} marker, whose own comment id
     *     <em>is</em> the epoch it would otherwise carry
     */
    @JsonCreator
    GithubMarkerJson(
            @JsonProperty("kind") @Nullable String kind,
            @JsonProperty("instance") @Nullable String instance,
            @JsonProperty("at") @Nullable String at,
            @JsonProperty("version") int version,
            @JsonProperty("reason") @Nullable String reason,
            @JsonProperty("task") @Nullable String task,
            @JsonProperty("intent") @Nullable String intent,
            @JsonProperty("epoch") @Nullable Long epoch) {
        this.kind = kind;
        this.instance = instance;
        this.at = at;
        this.version = version;
        this.reason = reason;
        this.task = task;
        this.intent = intent;
        this.epoch = epoch;
    }

    @JsonProperty("kind")
    @Nullable
    String kind() {
        return kind;
    }

    @JsonProperty("instance")
    @Nullable
    String instance() {
        return instance;
    }

    @JsonProperty("at")
    @Nullable
    String at() {
        return at;
    }

    @JsonProperty("version")
    int version() {
        return version;
    }

    @JsonProperty("reason")
    @Nullable
    String reason() {
        return reason;
    }

    @JsonProperty("task")
    @Nullable
    String task() {
        return task;
    }

    @JsonProperty("intent")
    @Nullable
    String intent() {
        return intent;
    }

    @JsonProperty("epoch")
    @Nullable
    Long epoch() {
        return epoch;
    }

    /**
     * Renders these fields as the one-line JSON of a structural marker.
     *
     * @return the serialized JSON object
     */
    String serialize() {
        try {
            return MAPPER.writeValueAsString(this);
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
    static Optional<GithubMarkerJson> deserialize(String json) {
        try {
            return Optional.of(MAPPER.readValue(json, GithubMarkerJson.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * The content identity these fields carry, or empty for a marker written
     * before FR11 (both parts absent) — a half-present pair is treated as
     * absent rather than half-built, since neither part identifies a comment
     * on its own.
     *
     * @return the content identity, or empty when this marker carries none
     */
    Optional<GithubCommentIdentity> identity() {
        if (task == null || intent == null) {
            return Optional.empty();
        }
        return Optional.of(new GithubCommentIdentity(task, intent));
    }
}
