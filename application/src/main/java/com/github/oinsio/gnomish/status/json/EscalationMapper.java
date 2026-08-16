package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;

/**
 * Maps the domain's {@link EscalationReport} sealed variants into {@link
 * EscalationDto} (task 6.5), by an exhaustive switch with no {@code default} arm.
 * {@code stage}/{@code at} render {@code null} for every variant: neither field
 * exists on {@code EscalationReport} itself, and no plumbing reachable from a
 * {@code StatusReport} (the report, its {@code StatusSnapshotHolder}, or the
 * {@code AttemptRecord}/{@code AttemptKey} history) attaches a stage name or
 * instant to a live escalation — see {@link EscalationDto}'s type-level note.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
final class EscalationMapper {

    private EscalationMapper() {}

    static EscalationDto toDto(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted exhausted ->
                new EscalationDto.AttemptsExhausted("attemptsExhausted", null, null, exhausted.limit());
            case EscalationReport.DecisionNeeded decisionNeeded ->
                new EscalationDto.DecisionNeeded(
                        "decisionNeeded", null, null, decisionNeeded.question(), decisionNeeded.options());
            case EscalationReport.CannotVerify cannotVerify ->
                new EscalationDto.CannotVerify(
                        "cannotVerify",
                        null,
                        null,
                        cannotVerify.check().label(),
                        cannotVerify.reason(),
                        cannotVerify.details());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                new EscalationDto.PipelineMismatch("pipelineMismatch", null, null, pipelineMismatch.staleStage());
            case EscalationReport.CannotExecute cannotExecute ->
                new EscalationDto.CannotExecute("cannotExecute", null, null, cannotExecute.cause());
        };
    }
}
