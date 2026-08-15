package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * The {@code task.json} contract's escalation-report shape, used both nested
 * inside {@link TaskOutcomeDto.Escalated#report()} and standalone as the
 * top-level {@code lastEscalation} field (FR5): one of the five {@link
 * com.github.oinsio.gnomish.domain.engine.EscalationReport} kinds, mirrored
 * 1:1 — {@link AttemptsExhausted}, {@link DecisionNeeded}, {@link CannotVerify},
 * {@link PipelineMismatch}, {@link CannotExecute}.
 *
 * <p>Uses {@link JsonTypeInfo.As#PROPERTY} rather than {@code EXISTING_PROPERTY}
 * — see {@link TaskOutcomeDto}'s type-level note for why this contract cannot
 * reuse {@code status.json}'s {@code EXISTING_PROPERTY} idiom.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EscalationReportDto.AttemptsExhausted.class, name = "attemptsExhausted"),
    @JsonSubTypes.Type(value = EscalationReportDto.DecisionNeeded.class, name = "decisionNeeded"),
    @JsonSubTypes.Type(value = EscalationReportDto.CannotVerify.class, name = "cannotVerify"),
    @JsonSubTypes.Type(value = EscalationReportDto.PipelineMismatch.class, name = "pipelineMismatch"),
    @JsonSubTypes.Type(value = EscalationReportDto.CannotExecute.class, name = "cannotExecute")
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
     * The recorded position named a stage absent from the current pipeline.
     *
     * @param type the discriminator, always {@code "pipelineMismatch"}
     * @param staleStage the recorded stage name absent from the pipeline
     */
    record PipelineMismatch(String type, String staleStage) implements EscalationReportDto {}

    /**
     * An executor infrastructure failure prevented running the stage.
     *
     * @param type the discriminator, always {@code "cannotExecute"}
     * @param cause the failure detail, stack trace preserved
     */
    record CannotExecute(String type, String cause) implements EscalationReportDto {}
}
