package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/**
 * The wire form of the hidden structural JSON {@link GithubMarker} renders into
 * and parses back out of a comment body — the data alone; {@link GithubMarkerJson}
 * is the codec over it. {@link JsonPropertyOrder} fixes wire-key order to the
 * component order, and every optional component is omitted entirely when
 * {@code null} (via {@link JsonInclude}), so a marker carrying none of them keeps
 * the original four-field {@code kind}/{@code instance}/{@code at}/{@code version}
 * shape and a marker written before FR11 still parses.
 *
 * <p>A record, following the precedent of {@code TaskJsonDto} in {@code adapters/git}:
 * a Jackson record whose only members are its components mutates cleanly under PIT;
 * the minion crash of hcoles/pitest#1285 is triggered by explicit methods inside a
 * record, which is why the codec's behaviour lives in {@link GithubMarkerJson}
 * rather than here. Jackson binds the canonical constructor without a
 * {@code @JsonCreator}.
 *
 * <p>Implements FR7 of add-tracker-port, FR11, FR13 of harden-task-branch-contract,
 * FR6 of add-parameter-count-gate (design D12).
 *
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
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"kind", "instance", "at", "version", "reason", "task", "intent", "epoch"})
record GithubMarkerFields(
        @JsonProperty("kind") @Nullable String kind,
        @JsonProperty("instance") @Nullable String instance,
        @JsonProperty("at") @Nullable String at,
        @JsonProperty("version") int version,
        @JsonProperty("reason") @Nullable String reason,
        @JsonProperty("task") @Nullable String task,
        @JsonProperty("intent") @Nullable String intent,
        @JsonProperty("epoch") @Nullable Long epoch) {}
