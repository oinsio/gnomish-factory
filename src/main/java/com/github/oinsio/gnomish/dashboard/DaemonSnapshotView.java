package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.Snapshot;

/**
 * The dashboard page's daemon-section view model: the four states the
 * section must render distinctly (FR3, FR4) — no snapshot has ever been
 * written here, a fresh/healthy snapshot, a stale snapshot from a
 * non-{@code stopped} lifecycle (dead daemon — design D3 generalizes
 * operator-guide rule 1 from {@code running} to any non-stopped state), and
 * a stale snapshot whose last lifecycle was {@code stopped} (a clean stop,
 * not an alert). Modeled as a sealed type, mirroring {@code
 * serveobservability.LifecycleState}, so a caller rendering the section
 * exhaustively switches over exactly these four cases with no {@code
 * default} arm to silently swallow a new one.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3, FR4 of add-dashboard-page.
 */
public sealed interface DaemonSnapshotView
        permits DaemonSnapshotView.Absent,
                DaemonSnapshotView.Fresh,
                DaemonSnapshotView.DeadDaemon,
                DaemonSnapshotView.StoppedStale {

    /**
     * No {@code snapshot.json} could be read (missing file, unreadable, or
     * malformed) — the daemon has not run here, or its output cannot be
     * trusted; render "daemon has not run here" rather than fail the page
     * (FR3).
     */
    record Absent() implements DaemonSnapshotView {}

    /**
     * The snapshot is within the staleness window ({@code now − writtenAt
     * <= k × intervalSeconds}, {@code k = 3}) — the daemon is presumed
     * alive regardless of lifecycle state.
     *
     * @param snapshot the parsed snapshot; never null
     */
    record Fresh(Snapshot snapshot) implements DaemonSnapshotView {}

    /**
     * The snapshot is stale and its last reported lifecycle state is
     * non-{@code stopped} ({@code running}, {@code draining}, or {@code
     * stopping}) — the daemon most likely crashed or hung (design D3).
     *
     * @param snapshot the parsed snapshot; never null
     */
    record DeadDaemon(Snapshot snapshot) implements DaemonSnapshotView {}

    /**
     * The snapshot is stale and its last reported lifecycle state is
     * {@code stopped} — a clean, deliberate exit; staleness here is
     * expected, not an alert (FR4).
     *
     * @param snapshot the parsed snapshot; never null
     */
    record StoppedStale(Snapshot snapshot) implements DaemonSnapshotView {}
}
