package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * The {@code state.json} contract's per-attempt {@code stop} object: {@code none}, {@code
 * decisionNeeded(question, options)} or {@code cannotVerify(check, reason, details)}, mirroring the
 * domain's {@link com.github.oinsio.gnomish.domain.engine.Stop} sealed type. The {@code check} of
 * {@code cannotVerify} is the check's label — the same wire form a {@code StateCheckDto}'s {@code
 * ref} and {@code task.json}'s {@code lastEscalation} use for a {@code CheckRef}.
 *
 * <p>Uses {@link JsonTypeInfo.As#PROPERTY} for the same reason as {@link StatePositionDto}. A
 * {@code stop.type} this build does not know fails the bind, so the document is unreadable rather
 * than read as a round that raised no stop.
 *
 * <p>Implements FR5, FR10 of make-checkpoint-gate-durable (design D3, D5).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StateStopDto.None.class, name = "none"),
    @JsonSubTypes.Type(value = StateStopDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = StateStopDto.CannotVerify.class, name = "cannotVerify")
})
public sealed interface StateStopDto {

    /**
     * The round raised no stop.
     *
     * @param type the discriminator, always {@code "none"}
     */
    record None(String type) implements StateStopDto {}

    /**
     * The executor asked a human before any check ran.
     *
     * @param type the discriminator, always {@code "decisionNeeded"}
     * @param question the question the executor asked
     * @param options the answer options offered, free text
     */
    record DecisionNeeded(String type, String question, List<String> options) implements StateStopDto {}

    /**
     * The verify chain could not reach a verdict.
     *
     * @param type the discriminator, always {@code "cannotVerify"}
     * @param check the label of the check whose verdict could not be obtained
     * @param reason why the verdict could not be obtained
     * @param details the check's supporting detail
     */
    record CannotVerify(String type, String check, String reason, String details) implements StateStopDto {}
}
