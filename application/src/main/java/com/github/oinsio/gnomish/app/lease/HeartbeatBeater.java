package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
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
 * <p><b>A tracker outage is one fault, not one per beat (FR4, UX3 of
 * harden-logging-observability).</b> Beating repeats on the beat interval for as long as the
 * instance holds the claim, so an unsuppressed WARN per failed beat floods the operator plane for
 * the whole outage — and multiplies by the number of held claims. Each claim's failures therefore
 * report to a {@link RepeatSuppressor} under its own key: the first failure (and any change of
 * fault) is the WARN, the beats in between are DEBUG, a counted roll-up returns at the quiet
 * period, and the beat that lands again emits one INFO closing the outage.
 *
 * <p>Implements FR8 of add-claim-heartbeat. Implements FR13 of harden-task-branch-contract.
 * Implements FR4 of harden-logging-observability.
 *
 * @param tracker the port the beat writes through
 * @param progress the engine-event-fed progress source for the payload
 * @param clock the source of the {@code alive-at} instant
 * @param suppressor the edge-logging owner for each claim's beat-failure streak
 */
record HeartbeatBeater(Tracker tracker, HeartbeatProgress progress, Clock clock, RepeatSuppressor suppressor) {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatBeater.class);

    /** What this beat learned about {@code ref}'s claim. */
    BeatOutcome beat(TaskRef ref) {
        String payload = HeartbeatPayload.render(progress.progressFor(ref.id()), clock.now());
        HeartbeatResult result;
        try {
            result = tracker.heartbeat(ref, payload);
        } catch (RuntimeException e) {
            logFailure(ref, e);
            return BeatOutcome.UNCONFIRMED;
        }
        if (result instanceof HeartbeatResult.ClaimGone) {
            log.info("claim gone for {}; surfacing to sink and dropping", ref.id());
            return BeatOutcome.CLAIM_GONE;
        }
        logRecovery(ref);
        return BeatOutcome.BEATEN;
    }

    /**
     * Drops {@code ref}'s streak, so a claim released mid-outage leaves nothing behind in the
     * process-lifetime suppressor map. Called from {@link InstanceHeartbeat#unregister}, which is
     * the one place a claim stops being beaten — by release, by terminal result, or by loss.
     */
    void forget(TaskRef ref) {
        suppressor.recovered(key(ref));
    }

    /**
     * Logs whichever edge the suppressor returns for this failed beat. The streak's reason is the
     * fault's own words, read here rather than at the log call so the throwable still rides every
     * form as the trailing argument; a different tracker fault restarts the streak and is announced.
     */
    private void logFailure(TaskRef ref, RuntimeException e) {
        String reason = FailureReason.of(e);
        switch (suppressor.failed(key(ref), reason)) {
            case RepeatOccurrence.First ignored ->
                log.warn(
                        OperatorEvent.HEARTBEAT_BEAT_FAILED.head() + "beat failed for {}; continuing: {}",
                        ref.id(),
                        reason,
                        e);
            case RepeatOccurrence.Repeat repeat ->
                log.debug("beat still failing for {} ({}x); continuing: {}", ref.id(), repeat.count(), reason, e);
            case RepeatOccurrence.RollUp rollUp ->
                log.warn(
                        OperatorEvent.HEARTBEAT_BEAT_FAILING_ROLLUP.head()
                                + "beat failing for {} {}x over {}; continuing: {}",
                        ref.id(),
                        rollUp.count(),
                        rollUp.elapsed(),
                        reason,
                        e);
        }
    }

    /** One INFO when a claim beats again, so the operator's last word on the outage is its end. */
    private void logRecovery(TaskRef ref) {
        suppressor
                .recovered(key(ref))
                .ifPresent(recovery -> log.info(
                        "beat recovered for {} after {} failure(s) over {}: last reason={}",
                        ref.id(),
                        recovery.occurrences(),
                        recovery.outage(),
                        recovery.reason()));
    }

    /** Namespaces one claim's streak away from the tick-level streak sharing this suppressor. */
    private static String key(TaskRef ref) {
        return "beat:" + ref.id();
    }
}
