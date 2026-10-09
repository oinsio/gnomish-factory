package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/**
 * A {@link TrackerAdapterFactory} that answers the daemon and the embedded dashboard with two
 * different trackers, told apart by the one thing the adapter context says about the caller: a
 * claiming caller hands its tenure record, a reader hands {@link ClaimEpochSource#NONE} (the
 * context's own contract, design D11 of supervise-daemon-loops-and-embed-dashboard). Lets a serve
 * spec fail or count the board's reads without touching the daemon's own tracker, through the
 * plugin seam production already offers — no production test seam.
 *
 * <p>Test fixture. Implements FR10 of supervise-daemon-loops-and-embed-dashboard.
 */
final class BoardReaderSplitFactory implements TrackerAdapterFactory {

    private final Tracker daemonTracker
    private final Tracker boardReader

    BoardReaderSplitFactory(Tracker daemonTracker, Tracker boardReader) {
        this.daemonTracker = daemonTracker
        this.boardReader = boardReader
    }

    @Override
    String type() {
        'github'
    }

    @Override
    Tracker create(TrackerAdapterContext context) {
        context.epochs().is(ClaimEpochSource.NONE) ? boardReader : daemonTracker
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture: refs are already canonical')
    }
}
