package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.JudgeUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the domain's usage types ({@link ExecutorUsage}, {@link ToolUsage}, {@link
 * JudgeUsage}, {@link TokenUsage}) into their wire-format DTOs (task 6.5, 10.1):
 * {@code Duration.toMillis()} for durations, a per-model map of {@link
 * TokenUsageDto} for token counts — this project has no {@code
 * jackson-datatype-jsr310}, so neither {@code Instant} nor {@code Duration} is
 * ever bound directly.
 *
 * <p>Implements FR5, FR9, NFR-C1, D12 of add-agent-executor.
 */
final class UsageMapper {

    private UsageMapper() {}

    static UsageDto toUsage(ExecutorUsage usage) {
        return new UsageDto(
                usage.wallTime() == null ? null : usage.wallTime().toMillis(),
                toTokensByModel(usage.tokensByModel()),
                toByTool(usage.tools()));
    }

    static JudgeUsageDto toJudgeUsage(JudgeUsage usage) {
        return new JudgeUsageDto(
                usage.perVote().stream().map(UsageMapper::toVote).toList());
    }

    private static List<ByToolDto> toByTool(List<ToolUsage> tools) {
        return tools.stream()
                .map(tool -> new ByToolDto(
                        tool.name(), tool.calls(), tool.totalDuration().toMillis()))
                .toList();
    }

    private static JudgeUsageDto.Vote toVote(Map<String, TokenUsage> tokensByModel) {
        return new JudgeUsageDto.Vote(toTokensByModel(tokensByModel));
    }

    private static Map<String, TokenUsageDto> toTokensByModel(Map<String, TokenUsage> tokensByModel) {
        Map<String, TokenUsageDto> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, usage) -> result.put(model, toTokenUsage(usage)));
        return result;
    }

    private static TokenUsageDto toTokenUsage(TokenUsage usage) {
        return new TokenUsageDto(usage.input(), usage.output(), usage.cacheCreation(), usage.cacheRead());
    }
}
