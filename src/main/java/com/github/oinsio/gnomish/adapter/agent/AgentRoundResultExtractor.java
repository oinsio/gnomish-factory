package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import java.time.Instant;
import java.util.List;

/**
 * Extracts one round's {@link AgentRoundResult} from the {@link
 * TimestampedEvent} list {@link StreamJsonParser#parse} produced, applying
 * design D3's two failure classes (FR4): the round's {@link
 * AgentEvent.ResultEvent} is essential — none present in the list throws
 * {@link MissingResultEventException}, an infrastructure failure the caller
 * (the eventual {@code CliStageExecutor}, task 6.5) is expected to let
 * propagate uncaught, mirroring how {@link
 * com.github.oinsio.gnomish.domain.engine.RoundExecution#execute} treats a
 * {@link StageExecutor} throw (NFR-R1). Telemetry is best-effort: {@code
 * tokensByModel} is derived from the result event's {@code modelUsage} (or
 * the flat {@code usage} fallback keyed by the init event's model) via {@link
 * TokenUsageMapper} (task 3.3); {@code tools} is derived from the top-level
 * tool trace {@link ToolTraceBuilder} builds and {@link ToolUsageAggregator}
 * summarizes (task 3.4); {@code wallTime} stays unset here, filled in by task
 * 4.4. Any telemetry derivation trouble degrades to an empty {@code
 * tokensByModel}/{@code tools} — the round stands regardless (NFR-R2). See
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
        AgentEvent.ResultEvent resultEvent = null;
        AgentEvent.InitEvent initEvent = null;
        for (TimestampedEvent timestamped : events) {
            AgentEvent event = timestamped.event();
            if (event instanceof AgentEvent.InitEvent init) {
                initEvent = init;
            }
            if (event instanceof AgentEvent.ResultEvent result) {
                resultEvent = result;
            }
        }
        if (resultEvent == null) {
            String initSessionId = initEvent == null ? null : initEvent.sessionId();
            throw new MissingResultEventException(initSessionId == null ? "unknown" : initSessionId);
        }
        var tokensByModel = tokenUsageMapper.toTokensByModel(resultEvent, initEvent);
        var trace = toolTraceBuilder.buildTrace(events, roundEnd);
        var tools = toolUsageAggregator.aggregate(trace);
        var usage = new ExecutorUsage(null, tools, tokensByModel);
        return new AgentRoundResult(resultEvent.sessionId(), resultEvent.result(), usage);
    }
}
