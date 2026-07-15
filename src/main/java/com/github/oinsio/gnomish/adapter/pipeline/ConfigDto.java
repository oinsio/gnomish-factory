package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * The {@code config.yaml} wire-format DTO (D2): the tree-wide schema version and
 * the optional pipeline-wide {@code autonomy} defaults. Deserialized by
 * {@link PipelineYaml}, then mapped to the pure domain and validated later
 * (tasks 5.3, 4.1) — the DTO enforces no rule itself.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * schemaVersion: "1"
 * autonomy:
 *   attemptLimit: 3
 * }</pre>
 *
 * <p>Both fields are nullable at the wire level so that a missing
 * {@code schemaVersion} (FR9) or a missing {@code autonomy} block is carried as
 * {@code null} for the validators/mapper to handle, rather than failing to
 * deserialize.
 *
 * <p>Implements FR1, FR9 (DTO shape), D2 of load-pipeline-config.
 *
 * @param schemaVersion the tree-wide schema version, or {@code null} when absent
 * @param autonomy the pipeline-wide autonomy defaults, or {@code null} when the
 *     block is absent
 */
public record ConfigDto(
        @Nullable String schemaVersion, @Nullable AutonomyDto autonomy) {}
