package com.github.oinsio.gnomish.serveobservability;

/**
 * One line of the serve daemon's ledger — append-only JSONL history of
 * terminal task outcomes and daemon lifecycle events (design D1, D5, D6):
 * {@link TaskOutcomeLine} for a terminal slot result carrying a final state,
 * {@link LifecycleLine} for a daemon start/stop, {@link RunSummaryLine} for
 * a drain run's aggregate, and — added by add-serve-sandbox-lifecycle
 * (NFR-O2) — {@link SweepActionLine} for one stop/dispose the
 * sandbox-lifecycle sweep performed and {@link SweepTickLine} for one
 * completed sweep tick's counts. Each variant is a standalone JSON document; the
 * ledger file itself is not a JSON document (no enclosing array) — one line,
 * one object (FR10).
 *
 * <p>This is the ledger's document model only — no writer, no appender, no
 * rotation or retention (later task groups); JSON serialization lives in
 * the {@code json} subpackage, mirroring {@link Snapshot}'s split.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR10, FR11, FR12, FR13 of add-serve-observability.
 */
public sealed interface LedgerLine
        permits TaskOutcomeLine, LifecycleLine, RunSummaryLine, SweepActionLine, SweepTickLine {

    /** The writing process's identity, common to every line type. */
    InstanceInfo instance();
}
