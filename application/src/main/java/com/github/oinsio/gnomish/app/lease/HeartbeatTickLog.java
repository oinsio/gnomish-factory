package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The edge logging of the beat thread's own tick, owned here so {@link InstanceHeartbeat} holds the
 * beating decisions and this holds what the operator is told when a whole tick fails.
 *
 * <p>The loop ticks once per beat interval for the life of the instance, so a fault that persists —
 * a throwing claim-loss sink, a broken progress source — costs one WARN per interval forever unless
 * it is suppressed. Every failure reports to a {@link RepeatSuppressor} and only the edges are
 * logged: first occurrence and periodic counted roll-ups at WARN, the ticks in between at DEBUG,
 * one INFO when a tick completes again (FR4, UX3 of harden-logging-observability).
 *
 * <p>The same suppressor is shared with {@link HeartbeatBeater}, which namespaces each claim's
 * beat-failure streak under {@code beat:<id>}; this one owns the single {@code tick} key.
 *
 * <p>Implements FR4 of harden-logging-observability.
 *
 * @param suppressor the edge-logging owner for the tick streak
 */
record HeartbeatTickLog(RepeatSuppressor suppressor) {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTickLog.class);

    /** The tick's own streak key, namespaced away from the per-claim {@code beat:<id>} keys. */
    private static final String STREAK = "tick";

    /**
     * Reports one failed tick and logs whichever edge the suppressor returns. The reason is read
     * here rather than at the log call so the throwable still rides every form as the trailing
     * argument; a different fault restarts the streak and is announced.
     *
     * @param e the fault that escaped the tick
     */
    void failed(RuntimeException e) {
        String reason = FailureReason.of(e);
        switch (suppressor.failed(STREAK, reason)) {
            case RepeatOccurrence.First ignored ->
                log.warn(
                        OperatorEvent.HEARTBEAT_TICK_FAILED.head() + "heartbeat tick failed; thread continues: {}",
                        reason,
                        e);
            case RepeatOccurrence.Repeat repeat ->
                log.debug("heartbeat tick still failing ({}x); thread continues: {}", repeat.count(), reason, e);
            case RepeatOccurrence.RollUp rollUp ->
                log.warn(
                        OperatorEvent.HEARTBEAT_TICK_FAILING_ROLLUP.head()
                                + "heartbeat tick failing {}x over {}; thread continues: {}",
                        rollUp.count(),
                        rollUp.elapsed(),
                        reason,
                        e);
        }
    }

    /** One INFO when a tick completes again, so the operator's last word is not the failure. */
    void recovered() {
        suppressor
                .recovered(STREAK)
                .ifPresent(recovery -> log.info(
                        "heartbeat tick recovered after {} failure(s) over {}: last reason={}",
                        recovery.occurrences(),
                        recovery.outage(),
                        recovery.reason()));
    }
}
