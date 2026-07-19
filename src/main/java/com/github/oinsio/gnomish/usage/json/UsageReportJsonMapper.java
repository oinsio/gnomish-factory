package com.github.oinsio.gnomish.usage.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.git.UsageRow;
import com.github.oinsio.gnomish.adapter.git.UsageTotals;
import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto;
import com.github.oinsio.gnomish.adapter.git.state.StateByToolDto;
import com.github.oinsio.gnomish.adapter.git.state.StateCheckDto;
import com.github.oinsio.gnomish.adapter.git.state.StateFindingDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJudgeUsageDto;
import com.github.oinsio.gnomish.adapter.git.state.StateTokenUsageDto;
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@code gnomish usage} reconstruction ({@link UsageRow} list plus {@link UsageTotals})
 * into its {@code "version": 1} JSON mini-contract DTO tree and serializes it — the {@code --json}
 * counterpart of {@code UsageTextRenderer} (task 5.6), built straight from the same {@link
 * UsageRow}s so no second git-history walk is needed. Every field is carried at full granularity
 * (per-model token maps, per-tool aggregates, per-vote judge usage) — never summed, unlike the
 * text rendering.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 */
public final class UsageReportJsonMapper {

    private final ObjectMapper mapper;

    /** Builds a mapper backed by a fresh {@link UsageJson#mapper()} instance. */
    public UsageReportJsonMapper() {
        this.mapper = UsageJson.mapper();
    }

    /**
     * Serializes {@code taskId}'s reconstructed usage as pretty-printed JSON matching the {@code
     * usage --json} v1 mini-contract.
     *
     * @param taskId the task id the rows/totals were reconstructed for
     * @param rows every detected round, oldest to newest; possibly empty
     * @param totals the cumulative usage across {@code rows}
     * @return the pretty-printed JSON document
     */
    public String serialize(String taskId, List<UsageRow> rows, UsageTotals totals) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toDto(taskId, rows, totals));
        } catch (JsonProcessingException e) {
            // The DTO tree is plain data with no cyclic references or unsupported types, so this
            // is unreachable in practice; wrap rather than declare a checked exception.
            throw new IllegalStateException("failed to serialize usage report", e);
        }
    }

    private UsageReportDto toDto(String taskId, List<UsageRow> rows, UsageTotals totals) {
        return new UsageReportDto(1, taskId, rows.stream().map(this::toRow).toList(), toExecutorUsage(totals));
    }

    private UsageRowDto toRow(UsageRow row) {
        StateAttemptDto attempt = row.attempt();
        return new UsageRowDto(
                row.stage(),
                attempt.round(),
                attempt.result(),
                attempt.startedAt(),
                attempt.checks().stream().map(UsageReportJsonMapper::toCheck).toList(),
                toExecutorUsage(attempt.executorUsage()),
                toJudgeUsage(attempt.judgeUsage()));
    }

    private static CheckDto toCheck(StateCheckDto check) {
        return new CheckDto(
                check.ref(),
                check.verdict(),
                check.findings().stream().map(UsageReportJsonMapper::toFinding).toList(),
                check.durationMillis(),
                check.reason(),
                check.details());
    }

    private static FindingDto toFinding(StateFindingDto finding) {
        return new FindingDto(finding.message(), finding.location(), finding.details());
    }

    private static ExecutorUsageDto toExecutorUsage(StateUsageDto usage) {
        return new ExecutorUsageDto(
                usage.wallMillis(), toTokensByModel(usage.tokensByModel()), toByTool(usage.byTool()));
    }

    private static ExecutorUsageDto toExecutorUsage(UsageTotals totals) {
        return new ExecutorUsageDto(totals.wallMillis(), toTokensByModel(totals.tokensByModel()), List.of());
    }

    private static JudgeUsageDto toJudgeUsage(StateJudgeUsageDto usage) {
        return new JudgeUsageDto(usage.perVote().stream()
                .map(vote -> new JudgeUsageDto.Vote(toTokensByModel(vote.tokensByModel())))
                .toList());
    }

    private static List<ByToolDto> toByTool(List<StateByToolDto> byTool) {
        return byTool.stream()
                .map(tool -> new ByToolDto(tool.name(), tool.calls(), tool.totalMillis()))
                .toList();
    }

    private static Map<String, TokenUsageDto> toTokensByModel(Map<String, StateTokenUsageDto> tokensByModel) {
        Map<String, TokenUsageDto> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, tokens) -> result.put(model, toTokenUsage(tokens)));
        return result;
    }

    private static TokenUsageDto toTokenUsage(StateTokenUsageDto tokens) {
        return new TokenUsageDto(tokens.input(), tokens.output(), tokens.cacheCreation(), tokens.cacheRead());
    }
}
