package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps between the domain's task-lifecycle types ({@link TaskContext}, {@link
 * Decision}, {@link TaskOutcome}, {@link EscalationReport}) and the {@code
 * task.json} v1 DTO tree ({@link TaskJsonDto}) — the mapper counterpart of
 * {@code status.json.StatusReportJsonMapper}, kept as a wholly separate contract
 * (design D5). Every sealed domain type is mapped through an exhaustive switch
 * with no {@code default} arm: a new variant fails to compile here until its
 * mapping is added.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 */
public final class TaskJsonMapper {

    private TaskJsonMapper() {}

    /**
     * Parses raw {@code task.json} text (e.g. a {@code git show} blob) into a
     * {@link TaskJsonDto}, refusing an unsupported or missing {@code "version"}
     * before attempting to bind the rest of the document (FR4). Unknown fields
     * elsewhere are tolerated per {@link TaskStateJson#mapper()}.
     *
     * @param json the raw {@code task.json} text; never null
     * @return the parsed and version-gated DTO
     * @throws UnsupportedStateFileVersionException if {@code "version"} is
     *     missing or is not {@code 1}
     */
    public static TaskJsonDto readDto(String json) {
        return StateFileVersionGate.readGated(TaskStateJson.mapper(), "task.json", json, 1, TaskJsonDto.class);
    }

    /**
     * Builds the {@code task.json} DTO tree from the domain pieces a {@code
     * TaskRepository} adapter has in hand — there is no single domain aggregate
     * bundling all of them yet (design D1: {@code TaskRepository} is a separate
     * seam from the engine).
     *
     * @param context the task's identity, description and decisions; never null
     * @param baseCommit the commit the task branch was created from; never null
     * @param createdAt when the task was created; never null
     * @param outcome the terminal outcome, or {@code null} while a visit is in
     *     progress
     * @param lastEscalation the last escalation report, or {@code null} if the
     *     task was never escalated
     * @return the equivalent {@code task.json} DTO tree
     */
    public static TaskJsonDto toDto(
            TaskContext context,
            String baseCommit,
            Instant createdAt,
            @Nullable TaskOutcome outcome,
            @Nullable EscalationReport lastEscalation) {
        return new TaskJsonDto(
                1,
                context.taskId(),
                context.title(),
                context.body(),
                createdAt.toString(),
                baseCommit,
                toDecisions(context.decisions()),
                outcome == null ? null : toOutcome(outcome),
                lastEscalation == null ? null : toEscalation(lastEscalation));
    }

    /**
     * Rebuilds the domain pieces bundled in {@link TaskJsonContent} from a parsed
     * {@code task.json} DTO tree. {@code outcome} and {@code lastEscalation} stay
     * at the DTO level rather than round-tripping into {@link TaskOutcome}/{@link
     * EscalationReport}: those domain types carry data {@code task.json} alone
     * does not have (e.g. {@code TaskOutcome}'s {@code finalState}, sourced from
     * {@code state.json} — task 1.3's contract — and {@code Aborted.failedAt}'s
     * structured {@link com.github.oinsio.gnomish.domain.engine.AttemptKey},
     * which the wire format renders only as an opaque label). Reconstructing a
     * full domain outcome from {@code task.json} alone belongs to whichever
     * caller later joins it with {@code state.json}.
     *
     * @param dto the DTO tree to map; never null
     * @return the equivalent domain pieces, bundled
     */
    public static TaskJsonContent fromDto(TaskJsonDto dto) {
        TaskContext context = new TaskContext(dto.taskId(), dto.title(), dto.body(), fromDecisions(dto.decisions()));
        EscalationReport lastEscalation = dto.lastEscalation() == null ? null : fromEscalation(dto.lastEscalation());
        return new TaskJsonContent(
                context, dto.baseCommit(), Instant.parse(dto.createdAt()), dto.outcome(), lastEscalation);
    }

    private static List<TaskDecisionDto> toDecisions(List<Decision> decisions) {
        return decisions.stream()
                .map(decision -> new TaskDecisionDto(
                        decision.body(),
                        decision.author(),
                        decision.stage(),
                        decision.time() == null ? null : decision.time().toString()))
                .toList();
    }

    private static List<Decision> fromDecisions(List<TaskDecisionDto> decisions) {
        return decisions.stream()
                .map(dto -> new Decision(
                        dto.body(), dto.stage(), dto.author(), dto.at() == null ? null : Instant.parse(dto.at())))
                .toList();
    }

    private static TaskOutcomeDto toOutcome(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> new TaskOutcomeDto.Completed("completed");
            case TaskOutcome.Paused paused -> new TaskOutcomeDto.Paused("paused", paused.passedStage());
            case TaskOutcome.Escalated escalated ->
                new TaskOutcomeDto.Escalated("escalated", toEscalation(escalated.report()));
            case TaskOutcome.Aborted aborted ->
                new TaskOutcomeDto.Aborted("aborted", aborted.failedAt().toString(), aborted.cause());
        };
    }

    private static EscalationReportDto toEscalation(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted exhausted ->
                new EscalationReportDto.AttemptsExhausted("attemptsExhausted", exhausted.limit());
            case EscalationReport.DecisionNeeded decisionNeeded ->
                new EscalationReportDto.DecisionNeeded(
                        "decisionNeeded", decisionNeeded.question(), decisionNeeded.options());
            case EscalationReport.CannotVerify cannotVerify ->
                new EscalationReportDto.CannotVerify(
                        "cannotVerify", cannotVerify.check().label(), cannotVerify.reason(), cannotVerify.details());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                new EscalationReportDto.PipelineMismatch("pipelineMismatch", pipelineMismatch.staleStage());
            case EscalationReport.CannotExecute cannotExecute ->
                new EscalationReportDto.CannotExecute("cannotExecute", cannotExecute.cause());
        };
    }

    private static EscalationReport fromEscalation(EscalationReportDto dto) {
        return switch (dto) {
            case EscalationReportDto.AttemptsExhausted exhausted ->
                new EscalationReport.AttemptsExhausted(exhausted.limit());
            case EscalationReportDto.DecisionNeeded decisionNeeded ->
                new EscalationReport.DecisionNeeded(decisionNeeded.question(), decisionNeeded.options());
            case EscalationReportDto.CannotVerify cannotVerify ->
                new EscalationReport.CannotVerify(
                        // The label alone cannot rebuild a full CheckRef (index is not
                        // carried in the wire format); index 0 is a placeholder — no
                        // caller reconstructs a CheckRef's index from task.json today.
                        new CheckRef(0, cannotVerify.check()), cannotVerify.reason(), cannotVerify.details());
            case EscalationReportDto.PipelineMismatch pipelineMismatch ->
                new EscalationReport.PipelineMismatch(pipelineMismatch.staleStage());
            case EscalationReportDto.CannotExecute cannotExecute ->
                new EscalationReport.CannotExecute(cannotExecute.cause());
        };
    }
}
