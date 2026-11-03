package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code task.json} contract's escalation-report shape, used both nested
 * inside {@link TaskOutcomeDto.Escalated#report()} and standalone as the
 * top-level {@code lastEscalation} field (FR5): one of the six {@link
 * com.github.oinsio.gnomish.domain.engine.EscalationReport} kinds, mirrored
 * 1:1 — {@link AttemptsExhausted}, {@link DecisionNeeded}, {@link CannotVerify},
 * {@link PipelineMismatch}, {@link CannotExecute}, {@link BudgetExhausted}.
 *
 * <p>Uses {@link JsonTypeInfo.As#PROPERTY} rather than {@code EXISTING_PROPERTY}
 * — see {@link TaskOutcomeDto}'s type-level note for why this contract cannot
 * reuse {@code status.json}'s {@code EXISTING_PROPERTY} idiom.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR7 of define-executor-contract.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EscalationReportDto.AttemptsExhausted.class, name = "attemptsExhausted"),
    @JsonSubTypes.Type(value = EscalationReportDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = EscalationReportDto.CannotVerify.class, name = "cannotVerify"),
    @JsonSubTypes.Type(value = EscalationReportDto.PipelineMismatch.class, name = "pipelineMismatch"),
    @JsonSubTypes.Type(value = EscalationReportDto.CannotExecute.class, name = "cannotExecute"),
    @JsonSubTypes.Type(value = EscalationReportDto.BudgetExhausted.class, name = "budgetExhausted")
})
public sealed interface EscalationReportDto {

    /**
     * The resolved attempt limit was hit.
     *
     * @param type the discriminator, always {@code "attemptsExhausted"}
     * @param limit the resolved attempt limit that was reached
     */
    record AttemptsExhausted(String type, int limit) implements EscalationReportDto {}

    /**
     * A human decision was requested.
     *
     * @param type the discriminator, always {@code "decisionNeeded"}
     * @param question what the human must decide
     * @param options the candidate answers, free text
     */
    record DecisionNeeded(String type, String question, List<String> options) implements EscalationReportDto {}

    /**
     * An infrastructure failure prevented verifying a check.
     *
     * @param type the discriminator, always {@code "cannotVerify"}
     * @param check the label of the verify-list check that could not be verified
     * @param reason why the verdict could not be obtained
     * @param details a preserved stack trace or extra detail
     */
    record CannotVerify(String type, String check, String reason, String details) implements EscalationReportDto {}

    /**
     * The recorded task no longer matches the run: exactly one of the two causes is present, the
     * other absent from the document (each omitted when null, so a stale-stage report written before
     * the executor cause existed keeps its bytes).
     *
     * @param type the discriminator, always {@code "pipelineMismatch"}
     * @param staleStage the recorded stage name absent from the pipeline, or null
     * @param executorVersion the executor whose describe version changed since the pin, or null (FR13
     *     of define-executor-contract)
     */
    record PipelineMismatch(
            String type,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String staleStage,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            ExecutorVersion executorVersion)
            implements EscalationReportDto {}

    /**
     * An executor whose describe version differs from the version the task pinned (FR13 of
     * define-executor-contract). Not a kind: the {@code executorVersion} cause of {@link
     * PipelineMismatch}.
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
     * close, so it left no attempt record to hang them on: this escalation is their
     * only place in {@code task.json}. The field is additive under contract v1 — a
     * document written before it existed binds the component to null, which the
     * canonical constructor normalizes to empty, so every pre-existing {@code
     * task.json} stays readable. The denials influence no derived field and never
     * appear in the attempt history.
     *
     * @param type the discriminator, always {@code "cannotExecute"}
     * @param cause the failure detail, stack trace preserved
     * @param denials the egress denials of the round that could not execute; possibly
     *     empty, and absent in documents written before the field existed
     */
    record CannotExecute(String type, String cause, List<StateDenialDto> denials) implements EscalationReportDto {

        public CannotExecute {
            denials = StateDenialMapper.absentAsEmpty(denials);
        }
    }

    /**
     * A round ended {@code failed/limit}: a bound of the stage was reached (FR7 of
     * define-executor-contract).
     *
     * @param type the discriminator, always {@code "budgetExhausted"}
     * @param bound the text naming the bound that was reached
     */
    record BudgetExhausted(String type, String bound) implements EscalationReportDto {}
}
