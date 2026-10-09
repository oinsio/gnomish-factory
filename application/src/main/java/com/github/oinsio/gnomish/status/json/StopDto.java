package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * The JSON contract's per-attempt {@code stop}: {@code none}, {@code decisionNeeded(question,
 * options)} or {@code cannotVerify(check, reason, details)}, mirroring the domain's {@code Stop}
 * sealed type with a lowerCamel {@code type} discriminator (spec.md). Always present on an
 * attempt — {@code none} for a round that raised no stop — and a pre-release amendment of contract
 * v1: the version stays 1.
 *
 * <p>Implements FR10, FR12 of make-checkpoint-gate-durable.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = StopDto.None.class, name = "none"),
    @JsonSubTypes.Type(value = StopDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = StopDto.CannotVerify.class, name = "cannotVerify")
})
public sealed interface StopDto {

    /**
     * The round raised no stop.
     *
     * @param type the discriminator, always {@code "none"}
     */
    record None(String type) implements StopDto {}

    /**
     * The executor asked a human before any check ran.
     *
     * @param type the discriminator, always {@code "decisionNeeded"}
     * @param question the question the round recorded
     * @param options the answer options the round recorded, possibly empty
     */
    record DecisionNeeded(String type, String question, List<String> options) implements StopDto {}

    /**
     * The verify chain could not reach a verdict.
     *
     * @param type the discriminator, always {@code "cannotVerify"}
     * @param check the label of the check whose verdict could not be obtained
     * @param reason why the verdict could not be obtained
     * @param details the check's supporting detail
     */
    record CannotVerify(String type, String check, String reason, String details) implements StopDto {}
}
