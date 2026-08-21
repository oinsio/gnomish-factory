package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.SweepVital;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the sandbox hygiene section's own alert conditions (NFR-O3, UX2 of
 * add-serve-sandbox-lifecycle) over a {@link SandboxHygieneView}: the sweep has not ticked for
 * longer than a multiple of its own cadence, consecutive ticks reached no claim verdict, and any
 * {@code tracked} stopped-orphan action in the rendered window.
 *
 * <p>Separate from {@link AlertConditionEvaluator} rather than folded into it: that evaluator's
 * input is one snapshot, while the third condition here is a LEDGER fact, and the section must
 * degrade on its own when either source is missing (FR3 of add-dashboard-page).
 *
 * <p>Both thresholds are constants, following {@link AlertConditionEvaluator}'s precedent for
 * rules 3 and 5: the dashboard is a separate, config-free reader of files a daemon wrote, and the
 * sweep's own cadence travels IN the snapshot ({@code vitals.sweep.intervalSeconds}, mirroring
 * {@code vitals.reaper.intervalSeconds}) precisely so the reader needs no daemon configuration to
 * judge staleness.
 *
 * <p>No sweep data raises nothing: an absent vital means the daemon predates this contract or has
 * not finished its first tick, which is not evidence of a stalled sweep.
 *
 * <p>Implements NFR-O3, UX2 of add-serve-sandbox-lifecycle.
 */
public final class SandboxHygieneAlertEvaluator {

    /** The sweep's own staleness multiplier {@code k}, same as the reaper's (design D10). */
    private static final int SWEEP_STALENESS_MULTIPLIER = 3;

    /** How many consecutive no-verdict ticks read as "cleanup silently stalled" (NFR-O3). */
    private static final int CONSECUTIVE_SKIPPED_THRESHOLD = 3;

    private SandboxHygieneAlertEvaluator() {}

    /**
     * Evaluates every applicable hygiene condition for {@code view} as of {@code now}.
     *
     * @param view the hygiene section's data; never null
     * @param now the instant to measure the tick-overdue threshold against; never null
     * @return the flagged conditions; empty if none fired or the view carries no sweep data
     */
    public static List<AlertCondition> evaluate(SandboxHygieneView view, Instant now) {
        List<AlertCondition> flagged = new ArrayList<>();
        SweepVital sweep = view.sweep();
        if (sweep != null) {
            if (tickOverdue(sweep, now)) {
                flagged.add(new AlertCondition.SweepTickOverdue());
            }
            if (sweep.consecutiveSkippedTicks() >= CONSECUTIVE_SKIPPED_THRESHOLD) {
                flagged.add(new AlertCondition.SweepTicksSkipped(sweep.consecutiveSkippedTicks()));
            }
        }
        for (SweepActionRow row : view.recentActions()) {
            if (row.isDeadInstanceSymptom()) {
                flagged.add(new AlertCondition.StoppedOrphanIncident(row.objectName(), row.taskKey(), row.reason()));
            }
        }
        return List.copyOf(flagged);
    }

    private static boolean tickOverdue(SweepVital sweep, Instant now) {
        Duration age = Duration.between(sweep.lastTickAt(), now);
        Duration threshold = Duration.ofSeconds(sweep.intervalSeconds() * SWEEP_STALENESS_MULTIPLIER);
        return age.compareTo(threshold) > 0;
    }
}
