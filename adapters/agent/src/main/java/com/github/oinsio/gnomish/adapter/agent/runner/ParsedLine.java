package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent;
import java.util.List;

/**
 * What one stdout line yielded (FR2, FR6 of extract-agent-round-runner): the dialect's own events,
 * which the runner keeps for the dialect and never reads, and the vendor-neutral progress events
 * the runner hands to the round's listeners as the line is read.
 *
 * <p>Implements FR2, FR6 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 * @param events the dialect's events from this line, in order; copied and unmodifiable, possibly
 *     empty
 * @param progress the progress the line implies, in delivery order; copied and unmodifiable,
 *     possibly empty
 */
public record ParsedLine<E>(List<E> events, List<AgentProgressEvent> progress) {

    public ParsedLine {
        events = List.copyOf(events);
        progress = List.copyOf(progress);
    }

    /**
     * The yield of a line the dialect does not recognize.
     *
     * @param <E> the dialect's own event type
     * @return a line with no event and no progress
     */
    public static <E> ParsedLine<E> nothing() {
        return new ParsedLine<>(List.of(), List.of());
    }
}
