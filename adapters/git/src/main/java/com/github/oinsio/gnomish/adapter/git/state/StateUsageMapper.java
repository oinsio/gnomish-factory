package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.JudgeUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the domain's usage types ({@link ExecutorUsage}, {@link ToolUsage},
 * {@link JudgeUsage}, {@link TokenUsage}) into their {@code state.json}
 * wire-format DTOs and back — the mapper counterpart of {@code
 * status.json.UsageMapper}, kept as a wholly separate contract (design D5).
 * {@code Duration.toMillis()} for durations, a per-model map of {@link
 * StateTokenUsageDto} for token counts — this project has no {@code
 * jackson-datatype-jsr310}, so neither {@code Instant} nor {@code Duration} is
 * ever bound directly.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 */
final class StateUsageMapper {

    private StateUsageMapper() {}

    static StateUsageDto toUsage(ExecutorUsage usage) {
        return new StateUsageDto(
                usage.wallTime() == null ? null : usage.wallTime().toMillis(),
                toTokensByModel(usage.tokensByModel()),
                toByTool(usage.tools()));
    }

    static ExecutorUsage fromUsage(StateUsageDto dto) {
        Duration wallTime = dto.wallMillis() == null ? null : Duration.ofMillis(dto.wallMillis());
        return new ExecutorUsage(wallTime, fromByTool(dto.byTool()), fromTokensByModel(dto.tokensByModel()));
    }

    static StateJudgeUsageDto toJudgeUsage(JudgeUsage usage) {
        return new StateJudgeUsageDto(
                usage.perVote().stream().map(StateUsageMapper::toVote).toList());
    }

    static JudgeUsage fromJudgeUsage(StateJudgeUsageDto dto) {
        return new JudgeUsage(
                dto.perVote().stream().map(StateUsageMapper::fromVote).toList());
    }

    private static List<StateByToolDto> toByTool(List<ToolUsage> tools) {
        return tools.stream()
                .map(tool -> new StateByToolDto(
                        tool.name(), tool.calls(), tool.totalDuration().toMillis()))
                .toList();
    }

    private static List<ToolUsage> fromByTool(List<StateByToolDto> tools) {
        return tools.stream()
                .map(dto -> new ToolUsage(dto.name(), dto.calls(), Duration.ofMillis(dto.totalMillis())))
                .toList();
    }

    private static StateJudgeUsageDto.Vote toVote(Map<String, TokenUsage> tokensByModel) {
        return new StateJudgeUsageDto.Vote(toTokensByModel(tokensByModel));
    }

    private static Map<String, TokenUsage> fromVote(StateJudgeUsageDto.Vote vote) {
        return fromTokensByModel(vote.tokensByModel());
    }

    private static Map<String, StateTokenUsageDto> toTokensByModel(Map<String, TokenUsage> tokensByModel) {
        Map<String, StateTokenUsageDto> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, usage) -> result.put(model, toTokenUsage(usage)));
        return result;
    }

    private static Map<String, TokenUsage> fromTokensByModel(Map<String, StateTokenUsageDto> tokensByModel) {
        Map<String, TokenUsage> result = new LinkedHashMap<>();
        tokensByModel.forEach((model, usage) -> result.put(model, fromTokenUsage(usage)));
        return result;
    }

    private static StateTokenUsageDto toTokenUsage(TokenUsage usage) {
        return new StateTokenUsageDto(usage.input(), usage.output(), usage.cacheCreation(), usage.cacheRead());
    }

    private static TokenUsage fromTokenUsage(StateTokenUsageDto dto) {
        return new TokenUsage(dto.input(), dto.output(), dto.cacheCreation(), dto.cacheRead());
    }
}
