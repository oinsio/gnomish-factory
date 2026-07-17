package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code lastEscalation} section: one of the five {@code
 * EscalationReport} kinds (spec.md). Every variant carries {@code stage}/{@code at}
 * per the canonical example's {@code decisionNeeded} envelope; the domain's {@code
 * EscalationReport} itself carries neither field, and no plumbing reachable from
 * {@code StatusReport}/{@code StatusSnapshotHolder} attaches a stage/instant to a
 * live escalation, so both render as JSON {@code null} for every variant — a known
 * gap in the mapper (see {@link StatusReportJsonMapper}), not fabricated data.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EscalationDto.AttemptsExhausted.class, name = "attemptsExhausted"),
    @JsonSubTypes.Type(value = EscalationDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = EscalationDto.CannotVerify.class, name = "cannotVerify"),
    @JsonSubTypes.Type(value = EscalationDto.PipelineMismatch.class, name = "pipelineMismatch"),
    @JsonSubTypes.Type(value = EscalationDto.CannotExecute.class, name = "cannotExecute")
})
public sealed interface EscalationDto {

    /**
     * The resolved attempt limit was hit.
     *
     * @param type the discriminator, always {@code "attemptsExhausted"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param limit the resolved attempt limit that was reached
     */
    record AttemptsExhausted(
            String type, @Nullable String stage, @Nullable String at, int limit) implements EscalationDto {}

    /**
     * A human decision was requested.
     *
     * @param type the discriminator, always {@code "decisionNeeded"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param question what the human must decide
     * @param options the candidate answers, free text
     */
    record DecisionNeeded(
            String type, @Nullable String stage, @Nullable String at, String question, List<String> options)
            implements EscalationDto {}

    /**
     * An infrastructure failure prevented verifying a check.
     *
     * @param type the discriminator, always {@code "cannotVerify"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param check the label of the verify-list check that could not be verified
     * @param reason why the verdict could not be obtained
     * @param details a preserved stack trace or extra detail
     */
    record CannotVerify(
            String type, @Nullable String stage, @Nullable String at, String check, String reason, String details)
            implements EscalationDto {}

    /**
     * The recorded position named a stage absent from the current pipeline.
     *
     * @param type the discriminator, always {@code "pipelineMismatch"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param staleStage the recorded stage name absent from the pipeline
     */
    record PipelineMismatch(
            String type, @Nullable String stage, @Nullable String at, String staleStage) implements EscalationDto {}

    /**
     * An executor infrastructure failure prevented running the stage.
     *
     * @param type the discriminator, always {@code "cannotExecute"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param cause the failure detail, stack trace preserved
     */
    record CannotExecute(
            String type, @Nullable String stage, @Nullable String at, String cause) implements EscalationDto {}
}
