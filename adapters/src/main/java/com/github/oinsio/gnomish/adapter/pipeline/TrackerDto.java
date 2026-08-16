package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code tracker} block of {@code config.yaml} (FR17 of add-tracker-port,
 * design D5): the core keys the loader itself knows — {@code type},
 * {@code abort-threshold}, and the heartbeat protocol constants
 * {@code heartbeat-interval} / {@code heartbeat-ttl-multiplier} (FR3 of
 * add-claim-heartbeat) — plus every other top-level key captured generically as
 * a raw {@code subsections} entry.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * tracker:
 *   type: github
 *   abort-threshold: 3
 *   heartbeat-interval: 5m
 *   heartbeat-ttl-multiplier: 3
 *   wip-limit: 10
 *   github:
 *     api-url: https://api.github.com
 *     repo: owner/repo
 * }</pre>
 *
 * <p>The adapter-owned subsection (here {@code github:}) is not a fixed field:
 * the loader stays adapter-agnostic (task 3.1 scope) and never interprets it —
 * {@link #subsections()} carries every unrecognized top-level key by name, raw,
 * via Jackson's {@link JsonAnySetter}, for the seam validator (task 3.2) and
 * the eventual adapter (task 4) to consume. The core kebab-case keys
 * ({@code abort-threshold}, {@code heartbeat-interval},
 * {@code heartbeat-ttl-multiplier}, {@code wip-limit}) each use an explicit
 * {@link JsonProperty} because the DTO field is camelCase while the wire key
 * is kebab-case, unlike every other DTO in this package, which is written
 * camelCase on the wire. Being explicit fields, they are bound directly and
 * never captured by the {@link JsonAnySetter} — they are loader-owned, not
 * adapter subsections. {@code wip-limit} in particular is always read from
 * this DTO parsed out of the factory's own clone's {@code config.yaml} (NFR-S3
 * of add-factory-serve) — a gnome working a task branch has no way to raise
 * the protocol constant.
 *
 * <p>Implements FR17 of add-tracker-port, FR3 of add-claim-heartbeat, FR6,
 * NFR-S3 of add-factory-serve.
 *
 * @param type the adapter discriminator, or {@code null} when omitted (a seam
 *     error at task 3.2, not this DTO's concern)
 * @param abortThreshold the declared abort-fuse threshold, or {@code null}
 *     when omitted — the mapper (task 3.1) defaults it to 3
 * @param heartbeatInterval the declared beat-interval duration string (e.g.
 *     {@code 5m}), or {@code null} when omitted — the mapper defaults it to 5
 *     minutes and parses it to a {@code Duration} (FR3 of add-claim-heartbeat)
 * @param heartbeatTtlMultiplier the declared TTL multiplier, or {@code null}
 *     when omitted — the mapper defaults it to 3 (FR3 of add-claim-heartbeat)
 * @param wipLimit the declared WIP limit, or {@code null} when omitted — the
 *     mapper defaults it to 10 (FR6 of add-factory-serve)
 * @param subsections every top-level {@code tracker} key other than the core
 *     keys, keyed by name, raw and uninterpreted
 */
public record TrackerDto(
        @Nullable String type,
        @Nullable @JsonProperty("abort-threshold") Integer abortThreshold,
        @Nullable @JsonProperty("heartbeat-interval") String heartbeatInterval,
        @Nullable @JsonProperty("heartbeat-ttl-multiplier") Integer heartbeatTtlMultiplier,
        @Nullable @JsonProperty("wip-limit") Integer wipLimit,
        @JsonAnySetter Map<String, Object> subsections) {

    public TrackerDto {
        subsections = Map.copyOf(subsections);
    }

    /** Convenience for construction outside Jackson binding (e.g. tests): no heartbeat/wip-limit keys, no subsections. */
    public TrackerDto(@Nullable String type, @Nullable Integer abortThreshold) {
        this(type, abortThreshold, null, null, null, Map.of());
    }

    /** Convenience for construction outside Jackson binding (e.g. tests): no heartbeat/wip-limit keys. */
    public TrackerDto(@Nullable String type, @Nullable Integer abortThreshold, Map<String, Object> subsections) {
        this(type, abortThreshold, null, null, null, subsections);
    }

    /**
     * Back-compat convenience predating {@code wip-limit} (task 3.1 of add-factory-serve): no
     * declared wip-limit.
     */
    public TrackerDto(
            @Nullable String type,
            @Nullable Integer abortThreshold,
            @Nullable String heartbeatInterval,
            @Nullable Integer heartbeatTtlMultiplier,
            Map<String, Object> subsections) {
        this(type, abortThreshold, heartbeatInterval, heartbeatTtlMultiplier, null, subsections);
    }
}
