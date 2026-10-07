package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The JSON contract's {@code position} section: {@code atStage(stage)},
 * {@code awaitingApproval(stage)} or {@code pipelineEnd}, mirroring the domain's {@code Position} sealed type with a
 * lowerCamel {@code type} discriminator (spec.md).
 *
 * <p>Implements FR11, M3 of add-manual-run; FR12 of make-checkpoint-gate-durable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PositionDto.AtStage.class, name = "atStage"),
    @JsonSubTypes.Type(value = PositionDto.AwaitingApproval.class, name = "awaitingApproval"),
    @JsonSubTypes.Type(value = PositionDto.PipelineEnd.class, name = "pipelineEnd")
})
public sealed interface PositionDto {

    /**
     * The task is positioned at the named stage.
     *
     * @param type the discriminator, always {@code "atStage"}
     * @param stage the stage name
     */
    record AtStage(String type, String stage) implements PositionDto {}

    /**
     * The task is held at the gate of the {@code manual} stage that passed (FR12 of
     * make-checkpoint-gate-durable).
     *
     * @param type the discriminator, always {@code "awaitingApproval"}
     * @param stage the name of the stage that passed
     */
    record AwaitingApproval(String type, String stage) implements PositionDto {}

    /**
     * The task has reached the explicit end of the pipeline.
     *
     * @param type the discriminator, always {@code "pipelineEnd"}
     */
    record PipelineEnd(String type) implements PositionDto {}
}
