package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.MismatchCause;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Objects;

/**
 * Maps the domain's {@link EscalationReport} kinds to and from {@code task.json}'s {@link
 * EscalationReportDto} — the shape both {@code outcome.escalated.report} and the top-level {@code
 * lastEscalation} carry — by exhaustive switches with no {@code default} arm, so a new kind fails
 * to compile here until it is mapped both ways. A DTO mapper: it rebuilds the domain value from the
 * wire and decides nothing (design D10 of define-executor-contract lists it beside {@link
 * StateJsonMapper} as an allowed constructor of {@link EscalationReport.CannotExecute}).
 *
 * <p>Moved out of {@link TaskJsonMapper} when the sixth kind arrived (task 4.2 of
 * define-executor-contract), as {@link StateStopMapper} was moved out of {@link StateJsonMapper}:
 * the escalation vocabulary is its own wire contract with its own round-trip spec.
 *
 * <p>Annotated {@link UntrustedExit} like {@link TaskJsonMapper}: it writes the reports' carried
 * text verbatim into the branch document, and re-mints each one as branch text on the way back
 * (design D3 of type-untrusted-text).
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR7 of define-executor-contract.
 */
@UntrustedExit
final class TaskEscalationMapper {

    private TaskEscalationMapper() {}

    static EscalationReportDto toDto(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted exhausted ->
                new EscalationReportDto.AttemptsExhausted("attemptsExhausted", exhausted.limit());
            case EscalationReport.DecisionNeeded decisionNeeded ->
                new EscalationReportDto.DecisionNeeded(
                        "decisionNeeded",
                        decisionNeeded.question().raw(),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::raw)
                                .toList());
            case EscalationReport.CannotVerify cannotVerify ->
                new EscalationReportDto.CannotVerify(
                        "cannotVerify",
                        cannotVerify.check().label().raw(),
                        cannotVerify.reason().raw(),
                        cannotVerify.details().raw());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                switch (pipelineMismatch.cause()) {
                    case MismatchCause.StaleStage stale ->
                        new EscalationReportDto.PipelineMismatch(
                                "pipelineMismatch", stale.stage().raw(), null);
                    case MismatchCause.ExecutorVersion changed ->
                        new EscalationReportDto.PipelineMismatch(
                                "pipelineMismatch",
                                null,
                                new EscalationReportDto.ExecutorVersion(
                                        changed.executor().raw(),
                                        changed.recorded().raw(),
                                        changed.current().raw()));
                };
            case EscalationReport.CannotExecute cannotExecute ->
                new EscalationReportDto.CannotExecute(
                        "cannotExecute",
                        cannotExecute.cause().raw(),
                        StateDenialMapper.toDtos(cannotExecute.denials()));
            case EscalationReport.BudgetExhausted budgetExhausted ->
                new EscalationReportDto.BudgetExhausted(
                        "budgetExhausted", budgetExhausted.bound().raw());
        };
    }

    static EscalationReport fromDto(EscalationReportDto dto) {
        return switch (dto) {
            case EscalationReportDto.AttemptsExhausted exhausted ->
                new EscalationReport.AttemptsExhausted(exhausted.limit());
            case EscalationReportDto.DecisionNeeded decisionNeeded ->
                // The branch-document re-mint (design D3): the question and its options come
                // back as the branch's text, whatever medium first produced them.
                new EscalationReport.DecisionNeeded(
                        UntrustedText.branchDocument(decisionNeeded.question()),
                        decisionNeeded.options().stream()
                                .map(UntrustedText::branchDocument)
                                .toList());
            case EscalationReportDto.CannotVerify cannotVerify ->
                new EscalationReport.CannotVerify(
                        // The label alone cannot rebuild a full CheckRef (index is not
                        // carried in the wire format); index 0 is a placeholder — no
                        // caller reconstructs a CheckRef's index from task.json today.
                        new CheckRef(0, UntrustedText.branchDocument(cannotVerify.check())),
                        UntrustedText.branchDocument(cannotVerify.reason()),
                        UntrustedText.branchDocument(cannotVerify.details()));
            case EscalationReportDto.PipelineMismatch pipelineMismatch ->
                new EscalationReport.PipelineMismatch(cause(pipelineMismatch));
            case EscalationReportDto.CannotExecute cannotExecute ->
                new EscalationReport.CannotExecute(
                        UntrustedText.branchDocument(cannotExecute.cause()),
                        StateDenialMapper.fromDtos(cannotExecute.denials()));
            case EscalationReportDto.BudgetExhausted budgetExhausted ->
                new EscalationReport.BudgetExhausted(UntrustedText.branchDocument(budgetExhausted.bound()));
        };
    }

    /**
     * The mismatch's cause as the document carries it: the executor cause when present, otherwise the
     * stale stage, which a document then must name — one that names neither fails closed, as an
     * unreadable envelope (FR13 of define-executor-contract).
     */
    private static MismatchCause cause(EscalationReportDto.PipelineMismatch dto) {
        var changed = dto.executorVersion();
        if (changed != null) {
            return new MismatchCause.ExecutorVersion(
                    UntrustedText.branchDocument(changed.executor()),
                    UntrustedText.branchDocument(changed.recorded()),
                    UntrustedText.branchDocument(changed.current()));
        }
        return new MismatchCause.StaleStage(UntrustedText.branchDocument(Objects.requireNonNull(
                dto.staleStage(), "task.json pipelineMismatch names neither staleStage nor executorVersion")));
    }
}
