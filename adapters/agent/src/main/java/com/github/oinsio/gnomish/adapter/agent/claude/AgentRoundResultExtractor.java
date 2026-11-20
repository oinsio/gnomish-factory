package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.adapter.agent.AgentRoundResult;
import com.github.oinsio.gnomish.adapter.agent.MissingResultEventException;
import com.github.oinsio.gnomish.adapter.agent.ToolUsageAggregator;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Extracts one round's {@link AgentRoundResult} from the {@link
 * TimestampedEvent} list {@link StreamJsonParser#parse} produced, applying
 * design D3's two failure classes (FR4): the round's {@link
 * AgentEvent.ResultEvent} is essential — none present in the list throws
 * {@link MissingResultEventException}, an infrastructure failure the executor round
 * reports as its {@code failed/infrastructure} result (NFR-R1). Telemetry is best-effort: {@code
 * byModel} (tokens and the reported cost per model) is derived from the result event's {@code modelUsage} (or
 * the flat {@code usage} fallback keyed by the init event's model) via {@link
 * TokenUsageMapper} (task 3.3); {@code tools} is derived from the top-level
 * tool trace {@link ToolTraceBuilder} builds and {@link ToolUsageAggregator}
 * summarizes (task 3.4); {@code wallTime} stays unset here, filled in by task
 * 4.4. Any telemetry derivation trouble degrades to an empty {@code
 * byModel}/{@code tools} — the round stands regardless (NFR-R2). See
 * {@link AgentRoundResult}'s javadoc for the extension seam.
 *
 * <p>"Unparseable" never reaches this class as a distinct case: a malformed
 * result-event line is already dropped one layer down, inside {@link
 * StreamJsonParser} (task 3.1) — it simply never becomes a {@code
 * ResultEvent} in the parsed list. From here, missing and unparseable are the
 * same observable symptom (FR4).
 *
 * <p>Implements FR4, FR6, NFR-R1, NFR-R2, NFR-O3, D3 of add-agent-executor.
 */
public final class AgentRoundResultExtractor {

    private final TokenUsageMapper tokenUsageMapper = new TokenUsageMapper();
    private final ToolTraceBuilder toolTraceBuilder = new ToolTraceBuilder();
    private final ToolUsageAggregator toolUsageAggregator = new ToolUsageAggregator();

    /**
     * Locates the round's {@link AgentEvent.ResultEvent} in {@code events} and
     * shapes it into an {@link AgentRoundResult}. The last {@code ResultEvent}
     * wins if more than one were somehow parsed (a stream should carry at most
     * one; taking the last is the conservative choice — it reflects the round's
     * final state rather than an earlier one).
     *
     * @param events the round's timestamped, parsed events, in wire order;
     *     never null
     * @param roundEnd the instant an orphaned top-level tool call's duration is
     *     measured to — the adapter process's exit instant (task 4.2); never
     *     null
     * @return the round's essential result, with best-effort telemetry; never
     *     null
     * @throws MissingResultEventException if {@code events} carries no {@link
     *     AgentEvent.ResultEvent} — an infrastructure failure of the round
     *     (FR4, NFR-R1)
     */
    public AgentRoundResult extract(List<TimestampedEvent> events, Instant roundEnd) {
        return extract(events, roundEnd, MissingResultEventException.UNKNOWN_VOLUME);
    }

    /**
     * As {@link #extract(List, Instant)}, additionally reporting the round's read
     * volume in the missing-result failure (FR5, D5 of fix-round-stdout-drain).
     * The round executions pass their {@link com.github.oinsio.gnomish.adapter.agent.StreamDrain}'s byte count here; the
     * two-argument overload above keeps the callers that have none.
     *
     * @param bytesRead the raw stdout bytes the round consumed, or {@code -1} when
     *     unknown
     */
    public AgentRoundResult extract(List<TimestampedEvent> events, Instant roundEnd, long bytesRead) {
        AgentEvent.ResultEvent resultEvent = last(events, AgentEvent.ResultEvent.class);
        if (resultEvent == null) {
            AgentEvent.InitEvent initEvent = last(events, AgentEvent.InitEvent.class);
            throw new MissingResultEventException(
                    initEvent == null ? null : initEvent.sessionId(), bytesRead, events.size());
        }
        return new AgentRoundResult(resultEvent.sessionId(), resultEvent.result(), usage(events, roundEnd));
    }

    /**
     * The round's best-effort telemetry alone (NFR-R2): tokens and cost per model from the last
     * result event — none when the stream carried no result event — and the tool aggregate of the
     * top-level trace. The one derivation {@link #extract} and {@link ClaudeDialect#usage} share
     * (FR3 of extract-agent-round-runner), so the dialect reports exactly what the round result
     * reported.
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
