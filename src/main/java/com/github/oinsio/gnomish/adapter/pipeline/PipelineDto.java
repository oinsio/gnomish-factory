package com.github.oinsio.gnomish.adapter.pipeline;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code pipeline.yaml} wire-format DTO (D2): the explicit, ordered list of
 * stage names that is the single source of truth for stage order (FR3, D4).
 * Deserialized by {@link PipelineYaml} in declaration order; emptiness and
 * name-uniqueness are validator concerns (task 4.2), not the DTO's.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * stages:
 *   - plan
 *   - implement
 *   - review
 * }</pre>
 *
 * <p>The list is nullable at the wire level so a {@code pipeline.yaml} missing
 * the {@code stages} key deserializes to {@code null} for the validators to
 * report, rather than failing to deserialize.
 *
 * <p>Implements FR1, FR3 (DTO shape), D2 of load-pipeline-config.
 *
 * @param stages the stage names in declaration order, or {@code null} when the
 *     {@code stages} key is absent
 */
public record PipelineDto(@Nullable List<String> stages) {}
