package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.status.Activity;
import com.github.oinsio.gnomish.status.Outcome;
import com.github.oinsio.gnomish.status.StatusReport;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link StatusReport} to its JSON-contract DTO tree and serializes it —
 * the top-level entry point for status-report JSON rendering (task 6.5). Every
 * sealed domain type is mapped through an exhaustive switch with no {@code
 * default} arm, mirroring the domain's own exhaustive-switch idiom (e.g. {@code
 * CheckRef.of}, {@code Outcome.from}): a new variant fails to compile here until
 * its mapping is added.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 */
public final class StatusReportJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link StatusJson#mapper()} instance. */
    public StatusReportJsonMapper() {
        this.mapper = StatusJson.mapper();
    }

    /**
     * Serializes {@code report} as pretty-printed JSON matching the v1 contract.
     *
     * @param report the report to serialize; never null
     * @return the pretty-printed JSON document
     */
    public String serialize(StatusReport report) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(report));
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice; wrap rather than declare
            // a checked exception on every caller.
            throw new IllegalStateException("failed to serialize StatusReport", e);
        }
    }

    /**
     * Builds the JSON-contract DTO tree from {@code report}.
     *
     * @param report the report to map; never null
     * @return the equivalent DTO tree
     */
    public StatusReportDto toDto(StatusReport report) {
        return new StatusReportDto(
                1,
                new TaskDto(report.taskId(), report.title()),
                toPosition(report),
                toActivity(report.activity()),
                toOutcome(report.outcome()),
                toCurrentStage(report),
                UsageMapper.toUsage(report.totals()),
                report.lastEscalation() == null ? null : EscalationMapper.toDto(report.lastEscalation()),
                toDecision(report.lastDecision()));
    }

    private static PositionDto toPosition(StatusReport report) {
        return report.currentStage() == null
                ? new PositionDto.PipelineEnd("pipelineEnd")
                : new PositionDto.AtStage("atStage", report.currentStage());
    }

    private static @Nullable ActivityDto toActivity(@Nullable Activity activity) {
        if (activity == null) {
            return null;
        }
        return switch (activity) {
            case Activity.Executing executing ->
                new ActivityDto.Executing(
                        "executing",
                        executing.since().toString(),
                        executing.currentTool(),
                        executing.currentTool() == null && executing.toolCalls() == 0 ? null : executing.toolCalls());
            case Activity.Verifying verifying ->
                new ActivityDto.Verifying(
                        "verifying",
                        verifying.checkRef().label(),
                        verifying.since().toString());
            case Activity.AwaitingInput awaitingInput ->
                new ActivityDto.AwaitingInput(
                        "awaitingInput",
                        awaitingInput.prompt(),
                        awaitingInput.since().toString());
        };
    }

    private static @Nullable OutcomeDto toOutcome(@Nullable Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case Outcome.Completed ignored -> new OutcomeDto.Completed("completed");
            case Outcome.Paused paused -> new OutcomeDto.Paused("paused", paused.passedStage());
            case Outcome.Escalated escalated ->
                new OutcomeDto.Escalated("escalated", EscalationMapper.toDto(escalated.report()));
            case Outcome.Aborted aborted ->
                new OutcomeDto.Aborted("aborted", aborted.failedAt().toString(), aborted.cause());
        };
    }

    private static @Nullable CurrentStageDto toCurrentStage(StatusReport report) {
        if (report.currentStage() == null) {
            return null;
        }
        Integer limit = report.attemptLimit();
        int resolvedLimit = limit == null ? 0 : limit;
        return new CurrentStageDto(report.attemptsUsed(), resolvedLimit, toAttempts(report.attempts()));
    }

    private static List<AttemptDto> toAttempts(List<AttemptRecord> attempts) {
        return attempts.stream().map(AttemptMapper::toDto).toList();
    }

    private static @Nullable DecisionDto toDecision(@Nullable Decision decision) {
        if (decision == null) {
            return null;
        }
        return new DecisionDto(
                decision.body(),
                decision.author(),
                decision.stage(),
                decision.time() == null ? null : decision.time().toString());
    }
}
