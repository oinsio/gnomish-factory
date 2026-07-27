package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code tracker} block of {@code config.yaml} (FR17 of add-tracker-port,
 * design D5): the two core keys the loader itself knows — {@code type} and
 * {@code abort-threshold} — plus every other top-level key captured generically
 * as a raw {@code subsections} entry.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * tracker:
 *   type: github
 *   abort-threshold: 3
 *   github:
 *     api-url: https://api.github.com
 *     repo: owner/repo
 * }</pre>
 *
 * <p>The adapter-owned subsection (here {@code github:}) is not a fixed field:
 * the loader stays adapter-agnostic (task 3.1 scope) and never interprets it —
 * {@link #subsections()} carries every unrecognized top-level key by name, raw,
 * via Jackson's {@link JsonAnySetter}, for the seam validator (task 3.2) and
 * the eventual adapter (task 4) to consume. {@code abort-threshold} uses an
 * explicit {@link JsonProperty} because the DTO field is camelCase while the
 * wire key is kebab-case, unlike every other DTO in this package, which is
 * written camelCase on the wire.
 *
 * <p>Implements FR17 of add-tracker-port.
 *
 * @param type the adapter discriminator, or {@code null} when omitted (a seam
 *     error at task 3.2, not this DTO's concern)
 * @param abortThreshold the declared abort-fuse threshold, or {@code null}
 *     when omitted — the mapper (task 3.1) defaults it to 3
 * @param subsections every top-level {@code tracker} key other than {@code
 *     type}/{@code abort-threshold}, keyed by name, raw and uninterpreted
 */
public record TrackerDto(
        @Nullable String type,
        @Nullable @JsonProperty("abort-threshold") Integer abortThreshold,
        @JsonAnySetter Map<String, Object> subsections) {

    public TrackerDto {
        subsections = Map.copyOf(subsections);
    }

    /** Convenience for construction outside Jackson binding (e.g. tests): no subsections. */
    public TrackerDto(@Nullable String type, @Nullable Integer abortThreshold) {
        this(type, abortThreshold, Map.of());
    }
}
