package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.SweepVital;
import org.jspecify.annotations.Nullable;

/**
 * Extracts the sweep vital out of whichever {@link DaemonSnapshotView} variant carries a snapshot
 * (NFR-O3 of add-serve-sandbox-lifecycle). Unlike {@link AlertConditionEvaluator}, a
 * {@link DaemonSnapshotView.StoppedStale} snapshot is read here too: a cleanly stopped daemon's
 * last sweep is still the truth about what is on the host — the kept environments it lists are
 * exactly what an operator returning to a stopped instance needs to see — while the alert
 * conditions, which claim something is wrong NOW, rightly stay silent for it.
 *
 * <p>Package-private helper split out of {@link DashboardRenderCycle} for file size, mirroring how
 * {@link DashboardHtmlFormatter} is split from {@link DashboardHtmlRenderer}.
 *
 * <p>Implements NFR-O3 of add-serve-sandbox-lifecycle.
 */
final class SweepVitalReader {

    private SweepVitalReader() {}

    /**
     * Reads the {@code vitals.sweep} entry from {@code view}, if it has one.
     *
     * @param view the classified snapshot view; never null
     * @return the sweep vital, or null when the view carries no snapshot or the snapshot predates
     *     the sweep contract
     */
    static @Nullable SweepVital read(DaemonSnapshotView view) {
        return switch (view) {
            case DaemonSnapshotView.Absent ignored -> null;
            case DaemonSnapshotView.Fresh fresh -> fresh.snapshot().vitals().sweep();
            case DaemonSnapshotView.DeadDaemon dead -> dead.snapshot().vitals().sweep();
            case DaemonSnapshotView.StoppedStale stopped ->
                stopped.snapshot().vitals().sweep();
        };
    }
}
