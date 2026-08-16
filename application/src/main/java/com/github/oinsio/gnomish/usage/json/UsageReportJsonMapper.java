package com.github.oinsio.gnomish.usage.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.app.port.git.UsageTotals;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.JudgeUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import com.github.oinsio.gnomish.domain.engine.Verdict;
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
 * <p>Source types are the domain's own ({@link AttemptRecord}, {@link CheckResult}, {@link
 * ExecutorUsage}, …), not {@code state.json}'s DTOs: this mapper renders a contract of its own and
 * must not be bound to the git adapter's wire format (FR12b, design D12 of split-into-modules).
 * The flattening rules below — lowerCamel discriminators, a verdict's payload flattened onto the
 * same {@link CheckDto} as sibling fields, {@code Duration.toMillis()} for durations — mirror
 * {@code status.json}'s {@code AttemptMapper}/{@code UsageMapper} exactly, which is what {@code
 * state.json}'s own mapper mirrors in the other direction (design D5), so the rendered document is
 * byte-identical to what the DTO-sourced mapping produced.
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
        AttemptRecord attempt = row.attempt();
        return new UsageRowDto(
                row.stage(),
                attempt.round(),
                toResult(attempt.result()),
                attempt.startedAt().toString(),
                attempt.checkResults().stream()
                        .map(UsageReportJsonMapper::toCheck)
                        .toList(),
                toExecutorUsage(attempt.executorUsage()),
                toJudgeUsage(attempt.judgeUsage()));
    }

    private static String toResult(AttemptRecord.Result result) {
        return switch (result) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "qualityFailure";
            case CANNOT_VERIFY -> "cannotVerify";
            case DECISION_NEEDED -> "decisionNeeded";
        };
    }

    private static CheckDto toCheck(CheckResult check) {
        long durationMillis = check.duration().toMillis();
        return switch (check.verdict()) {
            case Verdict.Pass pass ->
                new CheckDto(check.checkRef().label(), "pass", List.of(), durationMillis, null, null, pass.runUrl());
            case Verdict.Fail fail ->
                new CheckDto(check.checkRef().label(), "fail", toFindings(fail), durationMillis, null, null, null);
            case Verdict.CannotVerify cannotVerify ->
                new CheckDto(
                        check.checkRef().label(),
                        "cannotVerify",
                        List.of(),
                        durationMillis,
                        cannotVerify.reason(),
                        cannotVerify.details(),
                        null);
        };
    }

    private static List<FindingDto> toFindings(Verdict.Fail fail) {
        return fail.findings().stream()
                .map(finding -> new FindingDto(finding.message(), finding.location(), finding.details()))
                .toList();
    }

    private static ExecutorUsageDto toExecutorUsage(ExecutorUsage usage) {
        return new ExecutorUsageDto(
                usage.wallTime() == null ? null : usage.wallTime().toMillis(),
                toTokensByModel(usage.tokensByModel()),
                toByTool(usage.tools()));
    }

    private static ExecutorUsageDto toExecutorUsage(UsageTotals totals) {
        return new ExecutorUsageDto(totals.wallMillis(), toTokensByModel(totals.tokensByModel()), List.of());
    }

    private static JudgeUsageDto toJudgeUsage(JudgeUsage usage) {
        return new JudgeUsageDto(usage.perVote().stream()
                .map(vote -> new JudgeUsageDto.Vote(toTokensByModel(vote)))
                .toList());
    }

    private static List<ByToolDto> toByTool(List<ToolUsage> tools) {
        return tools.stream()
                .map(tool -> new ByToolDto(
                        tool.name(), tool.calls(), tool.totalDuration().toMillis()))
                .toList();
    }

    private static Map<String, TokenUsageDto> toTokensByModel(Map<String, TokenUsage> tokensByModel) {
        Map<String, TokenUsageDto> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, tokens) -> result.put(model, toTokenUsage(tokens)));
        return result;
    }

    private static TokenUsageDto toTokenUsage(TokenUsage tokens) {
        return new TokenUsageDto(tokens.input(), tokens.output(), tokens.cacheCreation(), tokens.cacheRead());
    }
}
