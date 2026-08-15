package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The {@code task.json} contract's {@code outcome} field: null while a visit is
 * in progress, reset to null at the start of each resumed visit (FR5), otherwise
 * one of the four {@link com.github.oinsio.gnomish.domain.engine.TaskOutcome}
 * kinds mirrored 1:1 — {@link Completed}, {@link Paused}, {@link Escalated},
 * {@link Aborted}.
 *
 * <p>Uses {@link JsonTypeInfo.As#PROPERTY} rather than {@code EXISTING_PROPERTY}
 * (unlike {@code status.json}'s sealed DTOs): this contract round-trips
 * bidirectionally (readers exist — resume, {@code status}/{@code usage}), and
 * Jackson 2.21's {@code EXISTING_PROPERTY} resolver does not populate a record's
 * canonical-constructor {@code type} component on deserialization, leaving it
 * {@code null}. {@code status.json}'s DTOs are serialize-only, so that gap never
 * surfaced there.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TaskOutcomeDto.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = TaskOutcomeDto.Paused.class, name = "paused"),
    @JsonSubTypes.Type(value = TaskOutcomeDto.Escalated.class, name = "escalated"),
    @JsonSubTypes.Type(value = TaskOutcomeDto.Aborted.class, name = "aborted")
})
public sealed interface TaskOutcomeDto {

    /**
     * Every stage passed; the pipeline reached its end.
     *
     * @param type the discriminator, always {@code "completed"}
     */
    record Completed(String type) implements TaskOutcomeDto {}

    /**
     * A manual checkpoint paused the run.
     *
     * @param type the discriminator, always {@code "paused"}
     * @param passedStage the stage that passed and triggered the pause
     */
    record Paused(String type, String passedStage) implements TaskOutcomeDto {}

    /**
     * A human is needed.
     *
     * @param type the discriminator, always {@code "escalated"}
     * @param report why the run escalated
     */
    record Escalated(String type, EscalationReportDto report) implements TaskOutcomeDto {}

    /**
     * A persistence failure broke the durability guarantee.
     *
     * @param type the discriminator, always {@code "aborted"}
     * @param failedAt human-readable identity of the round whose persist failed
     * @param cause the failure detail, stack trace preserved
     */
    record Aborted(String type, String failedAt, String cause) implements TaskOutcomeDto {}
}
