package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One beat-failure-taxonomy-aware {@code tracker.heartbeat} call (design D7, FR8), extracted from
 * {@link InstanceHeartbeat} for file size. An <i>infrastructure</i> failure (network/5xx) is logged
 * WARN and swallowed here — no fuse burned, {@link InstanceHeartbeat} retries the claim next tick. A
 * claim-gone answer is logged and reported back via the return value so the caller can drop the dead
 * claim from its held set and surface the loss; this class never touches that held set itself.
 *
 * <p>Implements FR8 of add-claim-heartbeat.
 */
final class HeartbeatBeater {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatBeater.class);

    private final Tracker tracker;
    private final HeartbeatProgress progress;
    private final Clock clock;

    HeartbeatBeater(Tracker tracker, HeartbeatProgress progress, Clock clock) {
        this.tracker = tracker;
        this.progress = progress;
        this.clock = clock;
    }

    /** Returns true if the tracker reports the claim gone (reaped or taken over). */
    boolean beat(TaskRef ref) {
        String payload = HeartbeatPayload.render(progress.progressFor(ref.id()), clock.now());
        HeartbeatResult result;
        try {
            result = tracker.heartbeat(ref, payload);
        } catch (RuntimeException e) {
            log.warn("beat failed for {}; continuing", ref.id(), e);
            return false;
        }
        if (result instanceof HeartbeatResult.ClaimGone) {
            log.info("claim gone for {}; surfacing to sink and dropping", ref.id());
            return true;
        }
        return false;
    }
}
