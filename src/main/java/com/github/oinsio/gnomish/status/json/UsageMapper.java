package com.github.oinsio.gnomish.status.json;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.JudgeUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import java.util.List;

/**
 * Maps the domain's usage types ({@link ExecutorUsage}, {@link ToolUsage}, {@link
 * JudgeUsage}) into their wire-format DTOs (task 6.5): {@code Duration.toMillis()}
 * for durations, plain {@code Long} boxing for optional token counts — this
 * project has no {@code jackson-datatype-jsr310}, so neither {@code Instant} nor
 * {@code Duration} is ever bound directly.
 *
 * <p>Implements FR11, M3, NFR-C1 of add-manual-run.
 */
final class UsageMapper {

    private UsageMapper() {}

    static UsageDto toUsage(ExecutorUsage usage) {
        return new UsageDto(
                usage.wallTime() == null ? null : usage.wallTime().toMillis(),
                usage.tokens() == null ? null : usage.tokens().inputTokens(),
                usage.tokens() == null ? null : usage.tokens().outputTokens(),
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

    private static JudgeUsageDto.Vote toVote(TokenUsage tokens) {
        return new JudgeUsageDto.Vote(tokens.inputTokens(), tokens.outputTokens());
    }
}
