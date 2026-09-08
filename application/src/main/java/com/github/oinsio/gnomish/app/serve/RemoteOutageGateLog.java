package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every log line {@link RemoteOutageGate} emits, extracted so the gate's own file stays a state
 * machine (process-invariants.md file-size target) — mirrors {@link SlotOutcomeLog}'s split from
 * its caller. One WARN on open, one ERROR the first time an outage crosses the sustained-open
 * threshold, DEBUG-only for every failed probe in between (the {@link
 * com.github.oinsio.gnomish.logtext.RepeatSuppressor} edge, deliberately never re-announced at
 * WARN — the state-transition WARN already named the fault, task 7.4's own "exactly once" rule),
 * and one INFO recovery line on close.
 *
 * <p>Implements FR14, NFR-O1, NFR-O3, UX6 of add-base-ref-resolution.
 */
final class RemoteOutageGateLog {

    private static final Logger log = LoggerFactory.getLogger(RemoteOutageGate.class);

    private RemoteOutageGateLog() {}

    /** The one-time WARN a closed-to-open transition emits, naming the target and the cause. */
    static void opened(String target, String reason) {
        log.warn(
                OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.head()
                        + "remote outage gate opened for {}: claims will be released with no tracker call until it"
                        + " closes: {}",
                target,
                reason);
    }

    /** The DEBUG-only line for one failed probe, whichever {@link RepeatOccurrence} form it took. */
    static void probeFailed(String target, RepeatOccurrence occurrence) {
        switch (occurrence) {
            case RepeatOccurrence.First first ->
                log.debug("remote outage gate for {} still failing: {}", target, first.reason());
            case RepeatOccurrence.Repeat repeat ->
                log.debug("remote outage gate for {} still failing ({}x): {}", target, repeat.count(), repeat.reason());
            case RepeatOccurrence.RollUp rollUp ->
                log.debug(
                        "remote outage gate for {} still failing {}x over {}: {}",
                        target,
                        rollUp.count(),
                        rollUp.elapsed(),
                        rollUp.reason());
        }
    }

    /** The one-shot ERROR an outage crossing the sustained-open threshold emits. */
    static void sustainedOpen(String target, Duration threshold, String lastError) {
        log.error(
                OperatorEvent.REMOTE_OUTAGE_GATE_SUSTAINED_OPEN.head()
                        + "remote outage gate for {} has been open longer than {}: still {}",
                target,
                threshold,
                lastError);
    }

    /** The one INFO recovery line a close emits, carrying the outage's duration and probe count. */
    static void closed(String target, Duration outage, int probeCount) {
        log.info(
                "remote outage gate closed for {}: outage lasted {} over {} failed probe(s)",
                target,
                outage,
                probeCount);
    }
}
