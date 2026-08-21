package com.github.oinsio.gnomish.dashboard;

import java.util.List;

/**
 * What {@link SweepActionAggregator} found in one ledger window: the bounded, newest-first rows
 * the hygiene section renders, and how many actions the window actually held — so the table can
 * state its truncation instead of implying the window was quiet (NFR-O3 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3 of add-serve-sandbox-lifecycle.
 *
 * @param rows the window's actions, newest first, bounded; never null, may be empty
 * @param total how many actions the window held before the bound was applied; never negative
 */
public record SweepActionWindow(List<SweepActionRow> rows, int total) {

    /** An empty window: no readable ledger file, or none carrying a sweep action. */
    public static final SweepActionWindow EMPTY = new SweepActionWindow(List.of(), 0);

    public SweepActionWindow {
        rows = List.copyOf(rows);
    }
}
