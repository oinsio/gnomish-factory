package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.adapter.agent.ToolUsageAggregator;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The Claude dialect's derivation of one round's best-effort telemetry from the {@link
 * TimestampedEvent} list its parse produced (FR3 of extract-agent-round-runner; NFR-R2, NFR-O3 of
 * add-agent-executor): {@code byModel} (tokens and the reported cost per model) from the last result
 * event's {@code modelUsage} (or the flat {@code usage} fallback keyed by the init event's model) via
 * {@link TokenUsageMapper}, and {@code tools} from the top-level tool trace {@link ToolTraceBuilder}
 * builds and {@link ToolUsageAggregator} summarizes. Any derivation trouble degrades to an empty
 * {@code byModel}/{@code tools}. Whether the round has its result event is the runner's judgement
 * ({@code RoundJudgement}), not this class's: the old round path's {@code extract}, which threw on a
 * missing result event, left with that path (task 5.1 of extract-agent-round-runner).
 *
 * <p>Implements FR6, NFR-R2, NFR-O3 of add-agent-executor; FR3 of extract-agent-round-runner.
 */
public final class AgentRoundResultExtractor {

    private final TokenUsageMapper tokenUsageMapper = new TokenUsageMapper();
    private final ToolTraceBuilder toolTraceBuilder = new ToolTraceBuilder();
    private final ToolUsageAggregator toolUsageAggregator = new ToolUsageAggregator();

    /**
     * The round's best-effort telemetry alone (NFR-R2): tokens and cost per model from the last
     * result event — none when the stream carried no result event — and the tool aggregate of the
     * top-level trace. The derivation {@link ClaudeDialect#usage} reports (FR3 of
     * extract-agent-round-runner).
     *
     * @param events the round's timestamped events, in wire order; never null
     * @param roundEnd the instant an unfinished top-level call's duration is measured to
     * @return the usage, with no wall time; never null
     */
    public ExecutorUsage usage(List<TimestampedEvent> events, Instant roundEnd) {
        AgentEvent.ResultEvent resultEvent = last(events, AgentEvent.ResultEvent.class);
        var byModel = resultEvent == null
                ? Map.<String, ModelUsage>of()
                : tokenUsageMapper.toByModel(resultEvent, last(events, AgentEvent.InitEvent.class));
        var tools = toolUsageAggregator.aggregate(toolTraceBuilder.buildTrace(events, roundEnd));
        return new ExecutorUsage(null, tools, byModel);
    }

    /**
     * The last event of {@code type} in {@code events}: the last result event wins if a stream
     * somehow carried more than one, since it reflects the round's final state; the last init
     * event names the session and the flat-usage fallback's model.
     */
    static <T extends AgentEvent> @Nullable T last(List<TimestampedEvent> events, Class<T> type) {
        T found = null;
        for (TimestampedEvent timestamped : events) {
            if (type.isInstance(timestamped.event())) {
                found = type.cast(timestamped.event());
            }
        }
        return found;
    }
}
