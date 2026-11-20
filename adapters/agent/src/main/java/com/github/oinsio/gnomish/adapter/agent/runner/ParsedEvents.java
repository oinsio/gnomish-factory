package com.github.oinsio.gnomish.adapter.agent.runner;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * The events one round parsed, in arrival order, each stamped with the instant its line was read
 * (FR2 of extract-agent-round-runner). The runner owns the list and appends as lines arrive; a
 * dialect receives a read-only view of it, so handing the round's history to {@link
 * AgentDialect#parse} per line copies nothing. A view handed to {@code parse} is read on the drain
 * thread that appends; the views handed to the other dialect methods are read after the drain was
 * joined, when nothing appends any more.
 *
 * <p>Implements FR2 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 * @param stamped the round's events; a read-only view, never a copy
 */
public record ParsedEvents<E>(List<Stamped<E>> stamped) {

    public ParsedEvents {
        stamped = Collections.unmodifiableList(stamped);
    }

    /**
     * No events: the history a round's first line is parsed against.
     *
     * @param <E> the dialect's own event type
     * @return an empty history
     */
    public static <E> ParsedEvents<E> none() {
        return new ParsedEvents<>(List.of());
    }

    /**
     * One event with the instant its line was read.
     *
     * @param <E> the dialect's own event type
     * @param at when the runner read the line, on the round's injected time source
     * @param event the dialect's event
     */
    public record Stamped<E>(Instant at, E event) {}
}
