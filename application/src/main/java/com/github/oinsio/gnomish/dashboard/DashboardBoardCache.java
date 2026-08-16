package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.BoardModel;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The board section's fetch cache (task 4.4): holds the last successfully fetched {@link
 * BoardModel} and its fetch time across render cycles, so a watch-mode cycle that falls between
 * board refreshes can reuse it instead of re-fetching (FR9, NFR-P1, design D4) and a fetch that
 * fails degrades to the last cached model plus a failure notice rather than losing it (FR3, design
 * D9). One instance is scoped to one dashboard invocation — a one-shot render uses a fresh,
 * never-fetched cache, so a first-fetch failure has nothing to fall back on and renders
 * "unavailable" (FR3's "no fetch has ever succeeded" case).
 *
 * <p>Deliberately decoupled from {@link com.github.oinsio.gnomish.app.port.tracker.Tracker}: the
 * caller supplies the fetch as a plain {@link Supplier}, so this class and its specs never need a
 * tracker fake, only a stub supplier.
 *
 * <p>Implements FR3, FR9, NFR-P1 of add-dashboard-page (design D9).
 */
public final class DashboardBoardCache {

    private @Nullable BoardModel lastModel;
    private @Nullable Instant lastFetchedAt;
    private @Nullable Instant lastAttemptedAt;

    /**
     * Reports whether a board refresh is due.
     *
     * @param now the instant to measure against; never null
     * @param cadence the board refresh cadence; never null
     * @return {@code true} if no fetch has ever been attempted, or {@code cadence} has elapsed
     *     since the last attempt (success or failure alike)
     */
    public boolean dueFor(Instant now, Duration cadence) {
        return lastAttemptedAt == null || Duration.between(lastAttemptedAt, now).compareTo(cadence) >= 0;
    }

    /**
     * Attempts a fresh fetch, recording the attempt regardless of outcome so {@link #dueFor}
     * gates on attempts, not only successes.
     *
     * @param fetch the board composition call; never null
     * @param now the instant to record as the fetch/attempt time; never null
     * @return a successful fetch as {@code (model, now, null)}; a failed fetch as {@code
     *     (lastModel, lastFetchedAt, failureMessage)} — the last good model (or {@code null} if
     *     none exists yet), marked with the new failure
     */
    public BoardSectionView refresh(Supplier<BoardModel> fetch, Instant now) {
        lastAttemptedAt = now;
        try {
            BoardModel model = fetch.get();
            lastModel = model;
            lastFetchedAt = now;
            return new BoardSectionView(model, now, null);
        } catch (RuntimeException fetchFailure) {
            return new BoardSectionView(lastModel, lastFetchedAt, failureMessage(fetchFailure));
        }
    }

    /**
     * The current cache contents with no fetch attempt — a render cycle between board refreshes
     * (FR9): the last model and fetch time, no failure notice (this cycle did not attempt a
     * refresh, so it did not fail one).
     *
     * @return the cached view; {@code (null, null, null)} before any fetch has ever succeeded
     */
    public BoardSectionView cached() {
        return new BoardSectionView(lastModel, lastFetchedAt, null);
    }

    private static String failureMessage(RuntimeException fetchFailure) {
        String message = fetchFailure.getMessage();
        return message != null ? message : fetchFailure.getClass().getSimpleName();
    }
}
