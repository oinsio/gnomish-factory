package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code outcome} section: {@code completed}, {@code
 * paused(passedStage)}, {@code escalated(report)}, or {@code aborted(failedAt,
 * cause)} (spec.md). Live-only, nullable mid-run.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OutcomeDto.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = OutcomeDto.Paused.class, name = "paused"),
    @JsonSubTypes.Type(value = OutcomeDto.Escalated.class, name = "escalated"),
    @JsonSubTypes.Type(value = OutcomeDto.Aborted.class, name = "aborted")
})
public sealed interface OutcomeDto {

    /**
     * Every stage passed; the pipeline reached its end.
     *
     * @param type the discriminator, always {@code "completed"}
     */
    record Completed(String type) implements OutcomeDto {}

    /**
     * A manual checkpoint paused the run.
     *
     * @param type the discriminator, always {@code "paused"}
     * @param passedStage the stage that passed and triggered the pause
     */
    record Paused(String type, String passedStage) implements OutcomeDto {}

    /**
     * A human is needed.
     *
     * @param type the discriminator, always {@code "escalated"}
     * @param report why the run escalated
     */
    record Escalated(String type, EscalationDto report) implements OutcomeDto {}

    /**
     * A persistence failure broke the durability guarantee.
     *
     * @param type the discriminator, always {@code "aborted"}
     * @param failedAt human-readable identity of the round whose persist failed,
     *     or {@code null} when unavailable
     * @param cause the failure detail
     */
    record Aborted(String type, @Nullable String failedAt, String cause) implements OutcomeDto {}
}
