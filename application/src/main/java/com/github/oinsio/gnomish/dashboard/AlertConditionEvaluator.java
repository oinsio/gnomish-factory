package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.FeedPhase;
import com.github.oinsio.gnomish.serveobservability.HeartbeatState;
import com.github.oinsio.gnomish.serveobservability.RemoteHealth;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the operator guide's dead-man's-switch rules 1–5 (design D9 of
 * add-serve-observability) over a single {@link DaemonSnapshotView}, for the
 * dashboard's daemon section to flag visually (task 3.4). Every rule here is
 * genuinely computable from one snapshot: rule 4 ("growing {@code
 * consecutiveFailures}") and half of rule 5 ("growing {@code
 * restartCount}") read as literally worded by the guide's reference monitor
 * script need the *previous* check's counters, which this evaluator does not
 * have — design D3 resolves this by scoping the dashboard's rules 1–5 to
 * what a single snapshot proves: a positive counter is itself evidence of
 * trouble, so both are flagged whenever non-zero rather than only on an
 * observed increase. The literal cross-check growth comparison stays with
 * the external monitor, which persists state between runs.
 *
 * <p>Rule 1 reuses {@link DaemonSnapshotView.DeadDaemon}'s classification
 * instead of recomputing {@code writtenAt} staleness — {@link
 * SnapshotReader} already owns that computation (design D3). Rules 2–5 are
 * only evaluated for {@link DaemonSnapshotView.Fresh} and {@link
 * DaemonSnapshotView.DeadDaemon}: a {@link DaemonSnapshotView.StoppedStale}
 * snapshot is a clean, deliberate exit (not an alert — FR4) and vitals frozen
 * at that exit are not evidence of anything ongoing; {@link
 * DaemonSnapshotView.Absent} carries no snapshot to read.
 *
 * <p>Implements FR4 of add-dashboard-page (design D3).
 */
public final class AlertConditionEvaluator {

    /**
     * Rule 3's "long" threshold for {@code feed.state == idleBlocked}
     * (operator guide reference script default): 30 minutes.
     */
    private static final Duration IDLE_BLOCKED_THRESHOLD = Duration.ofMinutes(30);

    /** The reaper's own staleness multiplier {@code k} (design D10), same as rule 1's. */
    private static final int REAPER_STALENESS_MULTIPLIER = 3;

    /**
     * The sustained-open escalation threshold (NFR-O3, UX6 of add-base-ref-resolution), matching
     * {@code RemoteOutageGate}'s own default. The dashboard reads only the persisted snapshot,
     * which carries no configured threshold of its own (a known limitation: a daemon configured
     * with a different {@code factory.serve.remote-sustained-open-threshold} escalates its own
     * ERROR at a different point than this label does) — until the snapshot carries the
     * configured value, this constant is the closest approximation available to a reader with no
     * other source of the daemon's configuration.
     */
    private static final Duration REMOTE_GATE_SUSTAINED_OPEN_THRESHOLD = Duration.ofHours(1);

    private AlertConditionEvaluator() {}

    /**
     * Evaluates every applicable rule for {@code view} as of {@code now}.
     *
     * @param view the classified snapshot view; never null
     * @param now the instant to measure staleness thresholds against; never null
     * @return the flagged conditions, in rule order 1–5; empty if none fired
     *     or {@code view} carries no snapshot to evaluate
     */
    public static List<AlertCondition> evaluate(DaemonSnapshotView view, Instant now) {
        if (view instanceof DaemonSnapshotView.StoppedStale || view instanceof DaemonSnapshotView.Absent) {
            return List.of();
        }

        List<AlertCondition> flagged = new ArrayList<>();
        if (view instanceof DaemonSnapshotView.DeadDaemon) {
            flagged.add(new AlertCondition.StaleWhileNotStopped());
        }

        Snapshot snapshot = snapshotOf(view);
        if (!snapshot.slots().entries().isEmpty()
                && snapshot.vitals().heartbeat().state() != HeartbeatState.RUNNING) {
            flagged.add(new AlertCondition.OccupiedSlotsHeartbeatNotRunning());
        }
        if (snapshot.feed().state() == FeedPhase.IDLE_BLOCKED
                && Duration.between(snapshot.feed().since(), now).compareTo(IDLE_BLOCKED_THRESHOLD) > 0) {
            flagged.add(new AlertCondition.LongIdleBlocked());
        }
        if (snapshot.tracker().consecutiveFailures() > 0) {
            flagged.add(new AlertCondition.TrackerFailuresPresent());
        }
        if (reaperStale(snapshot, now) || snapshot.vitals().reaper().restartCount() > 0) {
            flagged.add(new AlertCondition.ReaperDegraded());
        }
        for (RemoteHealth remote : snapshot.remote().values()) {
            if (remote.open()) {
                flagged.add(remoteGateOpen(remote, now));
            }
        }
        return List.copyOf(flagged);
    }

    /**
     * NFR-O3, UX6 of add-base-ref-resolution: a snapshot without a {@code remote} section (an old
     * document, or one written before any gate ever opened) raises no line at all — {@link
     * Snapshot#remote()} is never null, only empty, so the loop above simply has nothing to flag.
     */
    private static AlertCondition.RemoteGateOpen remoteGateOpen(RemoteHealth remote, Instant now) {
        boolean sustained = remote.openSince() != null
                && Duration.between(remote.openSince(), now).compareTo(REMOTE_GATE_SUSTAINED_OPEN_THRESHOLD) >= 0;
        return new AlertCondition.RemoteGateOpen(
                remote.target(), remote.openSince(), remote.lastError(), remote.nextProbeAt(), sustained);
    }

    /**
     * Rules 2–5 read {@link DaemonSnapshotView.Fresh} and {@link DaemonSnapshotView.DeadDaemon}
     * snapshots alike — the only two variants reachable here, {@link DaemonSnapshotView.StoppedStale}
     * and {@link DaemonSnapshotView.Absent} having already returned above.
     */
    private static Snapshot snapshotOf(DaemonSnapshotView view) {
        return switch (view) {
            case DaemonSnapshotView.Fresh fresh -> fresh.snapshot();
            case DaemonSnapshotView.DeadDaemon deadDaemon -> deadDaemon.snapshot();
            case DaemonSnapshotView.StoppedStale ignored -> throw new IllegalStateException("unreachable");
            case DaemonSnapshotView.Absent ignored -> throw new IllegalStateException("unreachable");
        };
    }

    private static boolean reaperStale(Snapshot snapshot, Instant now) {
        Duration age = Duration.between(snapshot.vitals().reaper().lastRunAt(), now);
        Duration threshold =
                Duration.ofSeconds(snapshot.vitals().reaper().intervalSeconds() * REAPER_STALENESS_MULTIPLIER);
        return age.compareTo(threshold) > 0;
    }
}
