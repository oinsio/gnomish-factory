package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * The events one agent round keeps for its dialect, within the retained cap of its {@link StdoutCaps}
 * (FR1, FR8, NFR-R1 of extract-agent-round-runner). A line costs its byte length when it yields
 * events, and nothing when it yields none — an unrecognized line keeps nothing. The first half of the
 * cap keeps the round's opening lines whole; once a line does not fit it, every later line goes to
 * the latest half, which drops its oldest lines, all their events, while it is over its half. The
 * line just kept is never dropped, since a line is at most half the retained cap ({@link
 * StdoutCaps}). The dialect therefore
 * always reads the round's opening (where a CLI names its session and model) and its end (where it
 * reports its result), and the heap stays bounded whatever the CLI writes in between.
 *
 * <p>{@link #view()} is the one {@link ParsedEvents} of the round: a read-only window over both
 * halves, never a copy, so handing the history to the dialect per line costs nothing. The reader's
 * thread appends and reads it while the round runs; the round's thread reads it only after joining
 * the reader.
 *
 * <p>Implements FR1, FR8, NFR-R1 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
final class RetainedEvents<E> {

    private final long openingBudget;
    private final long latestBudget;
    private final List<ParsedEvents.Stamped<E>> opening = new ArrayList<>();
    private final List<Kept<E>> latest = new ArrayList<>();
    private final ParsedEvents<E> view = new ParsedEvents<>(new Window());
    private int latestStart;
    private long openingBytes;
    private long latestBytes;
    private boolean openingClosed;
    private long dropped;

    /** @param retainedBytes the retained cap; at least twice the line cap */
    RetainedEvents(long retainedBytes) {
        this.openingBudget = retainedBytes / 2;
        this.latestBudget = retainedBytes - openingBudget;
    }

    /**
     * Keeps the events one line yielded.
     *
     * @param events the line's events, in order; possibly empty
     * @param at when the line was read
     * @param lineBytes the line's length, as read
     */
    void add(List<E> events, Instant at, long lineBytes) {
        if (events.isEmpty()) {
            return;
        }
        if (!openingClosed && openingBytes + lineBytes <= openingBudget) {
            events.forEach(event -> opening.add(new ParsedEvents.Stamped<>(at, event)));
            openingBytes += lineBytes;
            return;
        }
        openingClosed = true;
        for (int index = 0; index < events.size(); index++) {
            latest.add(new Kept<>(new ParsedEvents.Stamped<>(at, events.get(index)), index == 0 ? lineBytes : 0L));
        }
        latestBytes += lineBytes;
        while (latestBytes > latestBudget) {
            latestBytes -= latest.get(latestStart).cost();
            latestStart++;
            dropped++;
            while (latest.get(latestStart).cost() == 0) {
                latestStart++;
                dropped++;
            }
        }
        int removable = removable();
        latest.subList(0, removable).clear();
        latestStart -= removable;
    }

    /**
     * How much of the dropped prefix of the latest half to remove now: all of it once it outweighs
     * what the half still keeps, so a removal shifts no more than it frees — an amortized constant
     * cost per dropped event — and none before.
     *
     * <p>{@code @DoNotMutate} for the "provably equivalent mutant" reason of {@code testing.md}: any
     * answer from none to all of the dropped prefix leaves {@link #view()} the same — {@link Window}
     * reads from {@code latestStart}, which the removal moves by exactly what it removed — so a changed
     * boundary, a negated condition or a constant answer changes the memory a round holds and nothing a
     * test can observe. The removal itself stays in {@link #add}, where it is mutable and killed.
     */
    @DoNotMutate
    private int removable() {
        return latestStart * 2 > latest.size() ? latestStart : 0;
    }

    /** The kept events, in arrival order: the opening, then the latest. */
    ParsedEvents<E> view() {
        return view;
    }

    /** How many events were dropped from between the opening and the latest. */
    long dropped() {
        return dropped;
    }

    /** One event of the latest half with what it cost: its line's bytes on its first event, nothing on the rest. */
    private record Kept<E>(ParsedEvents.Stamped<E> stamped, long cost) {}

    /** The read-only window over both halves. */
    private final class Window extends AbstractList<ParsedEvents.Stamped<E>> {

        @Override
        public ParsedEvents.Stamped<E> get(int index) {
            return index < opening.size()
                    ? opening.get(index)
                    : latest.get(latestStart + index - opening.size()).stamped();
        }

        @Override
        public int size() {
            return opening.size() + latest.size() - latestStart;
        }
    }
}
