package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import java.time.Duration;
import java.time.LocalDate;

/**
 * One day's row in the dashboard history section: a UTC calendar date that
 * had a readable ledger file, paired with the {@code taskOutcome} counts
 * {@link LedgerAggregator#aggregate} accumulated from that day's lines, and
 * that same day's closed {@code remoteOutage} lines rolled up into a count
 * and total blocked duration (NFR-O3 of add-base-ref-resolution) — rendered
 * as a compact line under the outcome bars, never a bar of its own, since an
 * outage is not an outcome.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-dashboard-page (design D5). Implements NFR-O3 of
 * add-base-ref-resolution.
 *
 * @param date the UTC calendar date this row covers; never null
 * @param counts the day's outcome counts; never null
 * @param outageCount how many {@code remoteOutage} lines closed on this day; zero when none did
 * @param outageDuration the sum of those outages' durations; {@link Duration#ZERO} when none did
 */
public record DayOutcomeCounts(LocalDate date, OutcomeCounts counts, int outageCount, Duration outageDuration) {}
