package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * A {@code stage.yaml} output entry (D2): the stable artifact {@code id} that
 * later stages' internal inputs reference (FR4). Pipeline-wide id uniqueness and
 * reference resolution are validator concerns (task 4.3), not the DTO's.
 *
 * <p>Wire shape: {@code - id: impl-diff}.
 *
 * <p>The id is nullable at the wire level so an output entry that omits it
 * deserializes for the validators to report, rather than failing to deserialize.
 *
 * <p>Implements FR4 (DTO shape), D2 of load-pipeline-config.
 *
 * @param id the stable artifact identifier, or {@code null} when the entry omits
 *     it
 */
public record ArtifactOutputDto(@Nullable String id) {}
