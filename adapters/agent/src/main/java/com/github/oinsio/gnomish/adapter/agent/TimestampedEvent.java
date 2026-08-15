package com.github.oinsio.gnomish.adapter.agent;

import java.time.Instant;

/**
 * One {@link AgentEvent} paired with the {@link java.time.Instant} at which {@link
 * StreamJsonParser} read its line off the round's stdout (design D3). Stream-json
 * lines carry no timestamps of their own, so read time is the only timing signal
 * available; it must be captured live, inside the parse loop, as each line is
 * consumed — reconstructing it afterward from a plain {@code List<AgentEvent>} is
 * impossible, since the real timing information is already gone by the time the
 * loop returns (FR6, NFR-O3).
 *
 * <p>{@link ToolTraceBuilder} is the sole consumer: a top-level {@code tool_use}
 * block's {@code start} is its enclosing {@link AgentEvent.AssistantEvent}'s {@code
 * readAt}; a matching {@code tool_result}'s {@code readAt} closes the duration.
 *
 * <p>Implements FR6, NFR-O3, D3 of add-agent-executor.
 *
 * @param event the parsed event; never null
 * @param readAt the instant this event's line was read from the process's stdout;
 *     never null
 */
public record TimestampedEvent(AgentEvent event, Instant readAt) {

    public TimestampedEvent {
        requireNonNull(event, "event");
        requireNonNull(readAt, "readAt");
    }

    /**
     * Fails fast on a null {@code event}/{@code readAt}: the parser never omits
     * either. Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static void requireNonNull(Object value, String component) {
        if (value == null) {
            throw new NullPointerException("TimestampedEvent." + component + " must not be null");
        }
    }
}
