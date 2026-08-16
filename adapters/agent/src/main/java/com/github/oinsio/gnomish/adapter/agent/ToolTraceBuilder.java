package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.ToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds a round's top-level tool trace from its {@link TimestampedEvent} list
 * (design D3, FR6): only {@code tool_use}/{@code tool_result} pairs belonging to
 * a top-level event — {@link AgentEvent.AssistantEvent#parentToolUseId()} /
 * {@link AgentEvent.UserEvent#parentToolUseId()} both {@code null} — enter the
 * trace; nested subagent calls are excluded entirely, since counting them
 * alongside their enclosing top-level call would double-count the same work in
 * {@link com.github.oinsio.gnomish.domain.engine.ExecutorUsage#tools} aggregates.
 *
 * <p>Each top-level {@link ContentBlock.ToolUse} starts at its enclosing {@link
 * AgentEvent.AssistantEvent}'s read-time instant; its duration runs to the
 * read-time instant of the matching top-level {@link ContentBlock.ToolResult}
 * (matched by {@code tool_use_id}), or, when no top-level result ever arrives
 * (the process died mid-call), to the caller-supplied {@code roundEnd} instant —
 * the adapter's own process-exit reading (task 4.2), not something derivable
 * from the parsed events themselves (design D3).
 *
 * <p>Implements FR6, NFR-O3, D3 of add-agent-executor.
 */
public final class ToolTraceBuilder {

    /**
     * Builds the round's top-level tool trace in chronological order.
     *
     * @param events the round's timestamped events, in wire order; never null
     * @param roundEnd the instant an orphaned top-level tool call's duration is
     *     measured to (the process's exit instant); never null
     * @return the top-level {@link ToolCall}s, {@code seq} = chronological
     *     position within this trace; never null, possibly empty
     */
    public List<ToolCall> buildTrace(List<TimestampedEvent> events, Instant roundEnd) {
        Map<String, PendingCall> pending = new HashMap<>();
        List<PendingCall> order = new ArrayList<>();
        for (TimestampedEvent timestamped : events) {
            collectTopLevelToolUses(timestamped, pending, order);
            closeTopLevelToolResults(timestamped, pending);
        }
        List<ToolCall> trace = new ArrayList<>();
        int seq = 0;
        for (PendingCall call : order) {
            Instant end = call.end() != null ? call.end() : roundEnd;
            trace.add(new ToolCall(seq++, call.name(), call.start(), java.time.Duration.between(call.start(), end)));
        }
        return trace;
    }

    private void collectTopLevelToolUses(
            TimestampedEvent timestamped, Map<String, PendingCall> pending, List<PendingCall> order) {
        if (!(timestamped.event() instanceof AgentEvent.AssistantEvent assistant)) {
            return;
        }
        if (assistant.parentToolUseId() != null) {
            return;
        }
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ContentBlock.ToolUse toolUse) {
                PendingCall call = new PendingCall(toolUse.name(), timestamped.readAt());
                pending.put(toolUse.id(), call);
                order.add(call);
            }
        }
    }

    private void closeTopLevelToolResults(TimestampedEvent timestamped, Map<String, PendingCall> pending) {
        if (!(timestamped.event() instanceof AgentEvent.UserEvent user)) {
            return;
        }
        if (user.parentToolUseId() != null) {
            return;
        }
        for (ContentBlock block : user.content()) {
            if (block instanceof ContentBlock.ToolResult toolResult) {
                PendingCall call = pending.get(toolResult.toolUseId());
                if (call != null) {
                    call.close(timestamped.readAt());
                }
            }
        }
    }

    /** A top-level call awaiting its matching result, or already closed. */
    private static final class PendingCall {

        private final String name;
        private final Instant start;
        private @Nullable Instant end;

        PendingCall(String name, Instant start) {
            this.name = name;
            this.start = start;
        }

        void close(Instant end) {
            if (this.end == null) {
                this.end = end;
            }
        }

        String name() {
            return name;
        }

        Instant start() {
            return start;
        }

        @Nullable
        Instant end() {
            return end;
        }
    }
}
