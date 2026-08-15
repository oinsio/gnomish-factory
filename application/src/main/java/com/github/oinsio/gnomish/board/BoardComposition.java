package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The board's fetch+build composition (design D7 of add-dashboard-page): one {@link
 * Tracker#listReady(int)} call, one {@link Tracker#listOpen()} call, the backoff/WIP parameter
 * resolution ({@code trackerProperties.abortBackoffBase()}/{@code abortBackoffCap()}, {@code
 * trackerConfig.wipLimit()}), and the {@link BoardModel#build} invocation — extracted out of
 * {@code BoardCommand} so both it and the dashboard's board section (task 4.x) call the same
 * in-process composition rather than shelling out or reimplementing it (rejected alternatives in
 * D7). Never touches {@code System.out} or CLI argument types, so it stays usable from any caller
 * that already has a resolved {@link Tracker}, {@link TrackerConfig}, and {@link
 * FactoryProperties.Tracker}.
 *
 * <p>Implements FR5 of add-dashboard-page.
 */
public final class BoardComposition {

    private BoardComposition() {}

    /**
     * Fetches one {@code listReady}/{@code listOpen} pair and builds the {@link BoardModel} from
     * them, exactly as {@code gnomish board} does.
     *
     * @param tracker the resolved tracker to read from; never null
     * @param trackerConfig the project's validated {@code tracker} section, supplying {@code
     *     wipLimit()}; never null
     * @param trackerProperties the factory-wide backoff settings, supplying {@code
     *     abortBackoffBase()}/{@code abortBackoffCap()}; never null
     * @param clock the clock to take the observation instant from; never null
     * @param readyLimit the {@code listReady} window size; the caller's own concept (the board CLI's
     *     {@code --limit}, or a caller-chosen default) — also used to compute {@code truncated}
     * @return the assembled board model
     */
    public static BoardModel compose(
            Tracker tracker,
            TrackerConfig trackerConfig,
            FactoryProperties.Tracker trackerProperties,
            Clock clock,
            int readyLimit) {
        List<ReadyTask> ready = tracker.listReady(readyLimit);
        List<OpenTask> open = tracker.listOpen();
        boolean truncated = ready.size() == readyLimit;
        Instant now = clock.instant();
        return BoardModel.build(
                ready,
                open,
                truncated,
                now,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                now,
                open.size(),
                trackerConfig.wipLimit());
    }
}
