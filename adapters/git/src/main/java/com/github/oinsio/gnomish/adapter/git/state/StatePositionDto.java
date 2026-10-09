package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The {@code state.json} contract's {@code position} field: {@code atStage(stage)},
 * {@code awaitingApproval(stage)} or {@code pipelineEnd}, mirroring the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.Position} sealed type — the same shape
 * {@code status.json}'s {@code PositionDto} renders, but a distinct class in this
 * package (design D5: separate contract, own DTO tree).
 *
 * <p>Uses {@link JsonTypeInfo.As#PROPERTY} rather than {@code EXISTING_PROPERTY} —
 * see {@code TaskOutcomeDto}'s type-level note (task 1.2) for why this contract
 * cannot reuse {@code status.json}'s {@code EXISTING_PROPERTY} idiom: it silently
 * breaks record deserialization on this project's Jackson version.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR10 of make-checkpoint-gate-durable (design D5).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StatePositionDto.AtStage.class, name = "atStage"),
    @JsonSubTypes.Type(value = StatePositionDto.AwaitingApproval.class, name = "awaitingApproval"),
    @JsonSubTypes.Type(value = StatePositionDto.PipelineEnd.class, name = "pipelineEnd")
})
public sealed interface StatePositionDto {

    /**
     * The task is positioned at the named stage.
     *
     * @param type the discriminator, always {@code "atStage"}
     * @param stage the stage name
     */
    record AtStage(String type, String stage) implements StatePositionDto {}

    /**
     * The task is held at the gate of the {@code manual} stage that passed; only the approval
     * write moves it past (design D1, D5 of make-checkpoint-gate-durable). A build older than
     * this token reads it as an unknown subtype and fails closed.
     *
     * @param type the discriminator, always {@code "awaitingApproval"}
     * @param stage the name of the stage that passed
     */
    record AwaitingApproval(String type, String stage) implements StatePositionDto {}

    /**
     * The task has reached the explicit end of the pipeline.
     *
     * @param type the discriminator, always {@code "pipelineEnd"}
     */
    record PipelineEnd(String type) implements StatePositionDto {}
}
