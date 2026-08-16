package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

/**
 * A {@code stage.yaml} input entry (D2, D5): one of two variants selected by the
 * explicit {@code kind} discriminator — {@code source} (no producing stage) or
 * {@code internal} (references a producer output {@code id}). Jackson maps the
 * discriminator to the matching record; the adapter later maps these to the
 * sealed domain {@code ArtifactInput} (task 5.3).
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * inputs:
 *   - kind: source
 *   - kind: internal
 *     producerOutputId: plan-doc
 * }</pre>
 *
 * <p>Reference resolution (dangling/forward) is a validator concern (task 4.3),
 * not the DTO's.
 *
 * <p>Implements FR4 (DTO shape), D2, D5 of load-pipeline-config.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ArtifactInputDto.Source.class, name = "source"),
    @JsonSubTypes.Type(value = ArtifactInputDto.Internal.class, name = "internal")
})
public sealed interface ArtifactInputDto {

    /**
     * A {@code source} input: it arrives with the task working copy and needs no
     * producing stage (FR4).
     */
    record Source() implements ArtifactInputDto {}

    /**
     * An {@code internal} input: it references the {@code id} of an output
     * produced by an earlier stage (FR4). The reference is carried unresolved.
     *
     * @param producerOutputId the referenced producer output id, or {@code null}
     *     when the entry omits it (a validator concern, task 4.3)
     */
    record Internal(@Nullable String producerOutputId) implements ArtifactInputDto {}
}
