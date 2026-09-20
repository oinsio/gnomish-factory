package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * Maps the domain's {@link EscalationReport} sealed variants into {@link
 * EscalationDto} (task 6.5), by an exhaustive switch with no {@code default} arm.
 * {@code stage}/{@code at} render {@code null} for every variant: neither field
 * exists on {@code EscalationReport} itself, and no plumbing reachable from a
 * {@code StatusReport} (the report, its {@code StatusSnapshotHolder}, or the
 * {@code AttemptRecord}/{@code AttemptKey} history) attaches a stage name or
 * instant to a live escalation — see {@link EscalationDto}'s type-level note.
 *
 * <p>Annotated {@link UntrustedExit} for the same reason {@link AttemptMapper} is (design D2 of
 * type-untrusted-text): {@code status.json} is the machine plane, read by a parser rather than by
 * a person, so an escalation's carried text is written byte for byte — rendering it here would
 * corrupt the document {@code ConsoleIO.printMachine} exists to keep verbatim. The exit is the
 * document, not a helper: nothing outside this switch reads a carrier through it.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR3 of type-untrusted-text.
 */
@UntrustedExit
final class EscalationMapper {

    private EscalationMapper() {}

    static EscalationDto toDto(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted exhausted ->
                new EscalationDto.AttemptsExhausted("attemptsExhausted", null, null, exhausted.limit());
            case EscalationReport.DecisionNeeded decisionNeeded ->
                new EscalationDto.DecisionNeeded(
                        "decisionNeeded",
                        null,
                        null,
                        decisionNeeded.question().raw(),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::raw)
                                .toList());
            case EscalationReport.CannotVerify cannotVerify ->
                new EscalationDto.CannotVerify(
                        "cannotVerify",
                        null,
                        null,
                        cannotVerify.check().label().raw(),
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                new EscalationDto.PipelineMismatch(
                        "pipelineMismatch",
                        null,
                        null,
                        pipelineMismatch.staleStage().raw());
            case EscalationReport.CannotExecute cannotExecute ->
                // The denials of a round that left no attempt record ride the escalation
                // itself (FR2 of fix-denial-attribution-durability), through the one
                // finding shape a check's findings and an attempt's denials already use.
                new EscalationDto.CannotExecute(
                        "cannotExecute",
                        null,
                        null,
                        cannotExecute.cause().raw(),
                        AttemptMapper.toFindings(Denial.findings(cannotExecute.denials())));
        };
    }
}
