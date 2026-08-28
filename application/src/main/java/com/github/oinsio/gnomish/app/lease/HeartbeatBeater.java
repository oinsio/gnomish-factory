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
 * <p>The three answers are the {@link BeatOutcome} values: an infrastructure failure is {@code
 * UNCONFIRMED} rather than a silent {@code false}, because "the beat did not reach a verdict" is
 * what self-fencing counts (FR13 of harden-task-branch-contract) — the caller decides how long an
 * unconfirmed claim may stay unconfirmed before it freezes the run's writes.
 *
 * <p>Implements FR8 of add-claim-heartbeat. Implements FR13 of harden-task-branch-contract.
 */
record HeartbeatBeater(Tracker tracker, HeartbeatProgress progress, Clock clock) {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatBeater.class);

    /** What this beat learned about {@code ref}'s claim. */
    BeatOutcome beat(TaskRef ref) {
        String payload = HeartbeatPayload.render(progress.progressFor(ref.id()), clock.now());
        HeartbeatResult result;
        try {
            result = tracker.heartbeat(ref, payload);
        } catch (RuntimeException e) {
            log.warn("beat failed for {}; continuing", ref.id(), e);
            return BeatOutcome.UNCONFIRMED;
        }
        if (result instanceof HeartbeatResult.ClaimGone) {
            log.info("claim gone for {}; surfacing to sink and dropping", ref.id());
            return BeatOutcome.CLAIM_GONE;
        }
        return BeatOutcome.BEATEN;
    }
}
