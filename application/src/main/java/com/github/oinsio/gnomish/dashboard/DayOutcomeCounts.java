package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import java.time.LocalDate;

/**
 * One day's row in the dashboard history section: a UTC calendar date that
 * had a readable ledger file, paired with the {@code taskOutcome} counts
 * {@link LedgerAggregator#aggregate} accumulated from that day's lines.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-dashboard-page (design D5).
 *
 * @param date the UTC calendar date this row covers; never null
 * @param counts the day's outcome counts; never null
 */
public record DayOutcomeCounts(LocalDate date, OutcomeCounts counts) {}
