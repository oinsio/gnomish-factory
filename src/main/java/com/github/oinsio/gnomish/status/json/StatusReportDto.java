package com.github.oinsio.gnomish.status.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's top-level document (v1, spec.md): {@code version} (always
 * {@code 1}), {@code task}, {@code position}, {@code activity}, {@code outcome},
 * {@code currentStage}, {@code totals}, {@code lastEscalation}, {@code
 * lastDecision}. Every {@code null} field renders as JSON {@code null} — see
 * {@link StatusJson}.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param version the contract version; always {@code 1}
 * @param task the task's id and title
 * @param position where the task sits in its pipeline
 * @param activity what the engine is doing right now, or {@code null} when idle
 *     or built from state alone
 * @param outcome the terminal outcome of the run, or {@code null} mid-run
 * @param currentStage the current stage's attempt data, or {@code null} at {@code
 *     pipelineEnd}
 * @param totals cumulative executor usage for the whole task
 * @param lastEscalation the most recent escalation report, or {@code null} when
 *     none occurred
 * @param lastDecision the most recent human decision, or {@code null} when none
 *     were recorded
 */
public record StatusReportDto(
        int version,
        TaskDto task,
        PositionDto position,
        @Nullable ActivityDto activity,
        @Nullable OutcomeDto outcome,
        @Nullable CurrentStageDto currentStage,
        UsageDto totals,
        @Nullable EscalationDto lastEscalation,
        @Nullable DecisionDto lastDecision) {}
