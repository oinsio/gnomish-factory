package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * The {@code config.yaml} wire-format DTO (D2): the tree-wide schema version,
 * the optional pipeline-wide {@code autonomy} defaults, the optional
 * {@code tracker} section (FR17 of add-tracker-port), and the optional
 * {@code task-branch} section (FR1, D16 of add-base-ref-resolution).
 * Deserialized by {@link PipelineYaml}, then mapped to the pure domain and
 * validated later (tasks 5.3, 4.1, 3.1) — the DTO enforces no rule itself.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * schemaVersion: "1"
 * autonomy:
 *   attemptLimit: 3
 * tracker:
 *   type: github
 *   abort-threshold: 3
 * task-branch:
 *   base:
 *     default: main
 *     allowed:
 *       - pattern: main
 * }</pre>
 *
 * <p>All four fields are nullable at the wire level so that a missing
 * {@code schemaVersion} (FR9), a missing {@code autonomy} block, or a missing
 * {@code tracker} section is carried as {@code null} for the validators/mapper
 * to handle, rather than failing to deserialize. An absent {@code tracker}
 * section is valid on its own (FR17: tracker subcommands unavailable, {@code
 * run} unaffected), and so is an absent {@code task-branch} section (FR1 of
 * add-base-ref-resolution: no allowed base, no configured default). A
 * root-level {@code base:} is no longer a key of this schema and no alias for
 * the nested one (D16); it surfaces as an unknown field like any other.
 *
 * <p>Implements FR1, FR9 (DTO shape), D2 of load-pipeline-config; FR17 of
 * add-tracker-port (the {@code tracker} field); FR1, D16 of
 * add-base-ref-resolution (the {@code task-branch} field).
 *
 * @param schemaVersion the tree-wide schema version, or {@code null} when absent
 * @param autonomy the pipeline-wide autonomy defaults, or {@code null} when the
 *     block is absent
 * @param tracker the {@code tracker} section, or {@code null} when the whole
 *     section is absent (FR17 of add-tracker-port)
 * @param taskBranch the {@code task-branch} section, or {@code null} when the
 *     whole section is absent (FR1 of add-base-ref-resolution)
 */
public record ConfigDto(
        @Nullable String schemaVersion,
        @Nullable AutonomyDto autonomy,
        @Nullable TrackerDto tracker,
        @Nullable @JsonProperty("task-branch") TaskBranchDto taskBranch) {

    /** Convenience for call sites predating add-tracker-port: no tracker section. */
    public ConfigDto(@Nullable String schemaVersion, @Nullable AutonomyDto autonomy) {
        this(schemaVersion, autonomy, null, null);
    }

    /** Convenience for call sites predating add-base-ref-resolution: no task-branch section. */
    public ConfigDto(@Nullable String schemaVersion, @Nullable AutonomyDto autonomy, @Nullable TrackerDto tracker) {
        this(schemaVersion, autonomy, tracker, null);
    }
}
