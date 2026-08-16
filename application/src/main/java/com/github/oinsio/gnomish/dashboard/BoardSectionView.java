package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.BoardModel;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard page's board-section view model (task 3.1): captures the
 * degradation cases FR3/design D9 describe for the tracker board — a
 * successful fetch, a cached model that survived a refresh failure, or no
 * fetch having ever succeeded. Deliberately not sealed like {@link
 * DaemonSnapshotView}: the watch-loop cache that produces this view (task
 * group 4) does not exist yet, so this is a minimal capture of the three
 * states the renderer must distinguish, not the caching component itself.
 *
 * <p>The compact constructor enforces the one invariant the renderer relies
 * on: {@code fetchedAt} is present exactly when {@code model} is — a fetch
 * that never succeeded has no model and no fetch time to show (FR3).
 * {@code failureMessage} is independent of the other two: it is set on a
 * refresh failure regardless of whether a previously cached {@code model}
 * exists.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3, NFR-O1 of add-dashboard-page (design D9).
 *
 * @param model the last successfully fetched board model, or {@code null}
 *     when no fetch has ever succeeded
 * @param fetchedAt when {@code model} was fetched, or {@code null} exactly
 *     when {@code model} is {@code null}
 * @param failureMessage a summary of the most recent fetch failure, or
 *     {@code null} when the last (or only) fetch succeeded
 */
public record BoardSectionView(
        @Nullable BoardModel model,
        @Nullable Instant fetchedAt,
        @Nullable String failureMessage) {

    public BoardSectionView {
        if ((model == null) != (fetchedAt == null)) {
            throw new IllegalArgumentException(
                    "BoardSectionView.model and fetchedAt must be both null (never fetched) or both non-null");
        }
    }
}
