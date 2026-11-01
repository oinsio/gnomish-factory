package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code lastEscalation} section: one of the six {@code
 * EscalationReport} kinds (spec.md). Every variant carries {@code stage}/{@code at}
 * per the canonical example's {@code decisionNeeded} envelope; the domain's {@code
 * EscalationReport} itself carries neither field, and no plumbing reachable from
 * {@code StatusReport} attaches a stage/instant to an
 * escalation, so both render as JSON {@code null} for every variant — a known
 * gap in the mapper (see {@link StatusReportJsonMapper}), not fabricated data.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR7 of define-executor-contract.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EscalationDto.AttemptsExhausted.class, name = "attemptsExhausted"),
    @JsonSubTypes.Type(value = EscalationDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = EscalationDto.CannotVerify.class, name = "cannotVerify"),
    @JsonSubTypes.Type(value = EscalationDto.PipelineMismatch.class, name = "pipelineMismatch"),
    @JsonSubTypes.Type(value = EscalationDto.CannotExecute.class, name = "cannotExecute"),
    @JsonSubTypes.Type(value = EscalationDto.BudgetExhausted.class, name = "budgetExhausted")
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
     * The recorded task no longer matches the run: exactly one cause is present, the other omitted
     * (so a stale-stage line written before the executor cause existed keeps its bytes).
     *
     * @param type the discriminator, always {@code "pipelineMismatch"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param staleStage the recorded stage name absent from the pipeline, or null
     * @param executorVersion the executor whose describe version changed since the task pinned it, or
     *     null (FR13 of define-executor-contract)
     */
    record PipelineMismatch(
            String type,
            @Nullable String stage,
            @Nullable String at,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String staleStage,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            ExecutorVersion executorVersion)
            implements EscalationDto {}

    /**
     * The {@code executorVersion} cause of {@link PipelineMismatch} (FR13 of define-executor-contract).
     *
     * @param executor the executor's law name
     * @param recorded the version the task's pin carries
     * @param current the version the run's describe preflight obtained
     */
    record ExecutorVersion(String executor, String recorded, String current) {}

    /**
     * An executor infrastructure failure prevented running the stage.
     *
     * <p>{@code denials} carries the egress denials of the round that could not
     * execute (FR2 of fix-denial-attribution-durability). That round died before its
     * close and left no attempt record, so this escalation is the only place in the
     * document those denials can appear — a gnome that hung while attempting a blocked
     * egress would otherwise report nothing but the hang. Additive under contract v1:
     * an empty array when the round recorded none, and read as empty in documents
     * written before the field existed. It influences no other field.
     *
     * @param type the discriminator, always {@code "cannotExecute"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param cause the failure detail, stack trace preserved
     * @param denials the egress denials of the round that could not execute;
     *     possibly empty, never null
     */
    record CannotExecute(
            String type, @Nullable String stage, @Nullable String at, String cause, List<FindingDto> denials)
            implements EscalationDto {}

    /**
     * A round ended {@code failed/limit}: a bound of the stage was reached (FR7 of
     * define-executor-contract). The round itself is in the attempt history with its usage.
     *
     * @param type the discriminator, always {@code "budgetExhausted"}
     * @param stage the stage the escalation pertains to; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param at ISO-8601 UTC instant of the escalation; {@code null} — not
     *     available from the reachable domain data (see type-level note)
     * @param bound the text naming the bound that was reached
     */
    record BudgetExhausted(
            String type, @Nullable String stage, @Nullable String at, String bound) implements EscalationDto {}
}
