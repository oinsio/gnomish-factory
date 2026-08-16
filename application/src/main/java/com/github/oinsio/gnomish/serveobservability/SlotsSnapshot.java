package com.github.oinsio.gnomish.serveobservability;

import java.util.List;

/**
 * The snapshot's {@code slots} section (FR6): total slot capacity plus one
 * {@link SlotEntry} per occupied slot; free slots have no entry, so load is
 * {@code entries.size() / capacity}.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-serve-observability.
 *
 * @param capacity the total number of slots the daemon runs
 * @param entries one entry per occupied slot, in no particular order;
 *     defensively copied, unmodifiable, possibly empty
 */
public record SlotsSnapshot(int capacity, List<SlotEntry> entries) {

    public SlotsSnapshot {
        entries = List.copyOf(entries);
    }
}
