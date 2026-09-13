package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.Duration;
import java.time.Instant;

/**
 * What an operator is told about one remote's outages (task 7.4 of add-base-ref-resolution): the
 * target's identity, its failed-probe streak ({@link RepeatSuppressor}) and the one-shot
 * sustained-open latch, which together decide the <em>form</em> of each line.
 *
 * <p>Split from {@link RemoteOutageGate} as its own responsibility, not for line count: the gate
 * owns whether the remote is up, this owns what that costs the console — it says <em>opened</em>,
 * <em>probe failed</em>, <em>closed</em>, and nothing here can change its decisions. Levels and
 * {@code [GFnnn]} codes stay in {@link RemoteOutageGateLog}, the catalog site specs pin
 * ({@code logging.md}). Per-outage state is the latch alone; every call arrives from the gate's
 * guarded phases, so no synchronization lives here.
 *
 * <p>Implements FR14, NFR-O1, NFR-O3, UX6 of add-base-ref-resolution.
 */
final class RemoteOutageReporter {

    /**
     * The default sustained-open ERROR threshold, absent a configured one (task 7.4): an hour is
     * past "will recover shortly" and into "an operator should look", while staying comfortably
     * above the probe cap so a merely slow-to-recover remote does not trip its own backoff.
     */
    static final Duration DEFAULT_SUSTAINED_OPEN_THRESHOLD = Duration.ofHours(1);

    /**
     * The remote-target identity used where no richer one is threaded through: today one daemon
     * runs against exactly one clone/one remote (task 7.3's own scope decision), so a fixed literal
     * is a correct key — multi-remote support gives each gate its own identity instead of widening
     * this one.
     */
    static final String DEFAULT_TARGET = "origin";

    private final String target;
    private final RepeatSuppressor suppressor;
    private final RemoteOutageSustainedOpenWatch sustainedOpenWatch;

    /**
     * @param target the remote-target identity named in every line; never blank
     * @param suppressor the edge-logging owner for this target's failed-probe streak; never null
     * @param sustainedOpenThreshold how long an outage may run before the one-shot ERROR fires;
     *     never null, positive
     */
    RemoteOutageReporter(String target, RepeatSuppressor suppressor, Duration sustainedOpenThreshold) {
        this.target = target;
        this.suppressor = suppressor;
        this.sustainedOpenWatch = new RemoteOutageSustainedOpenWatch(sustainedOpenThreshold);
    }

    /** The target this reporter speaks for — also the identity the snapshot's health entry carries. */
    String target() {
        return target;
    }

    /**
     * The outage arrived: one WARN naming the fault, and the streak opened at DEBUG — the
     * suppressor's First occurrence stays DEBUG here, since the WARN already named the fault (task
     * 7.4's "exactly once per outage" rule).
     *
     * @param scrubbedReason the cause, already routed through {@code LogText}; never null
     */
    void opened(String scrubbedReason) {
        sustainedOpenWatch.reset();
        RemoteOutageGateLog.opened(target, scrubbedReason);
        RemoteOutageGateLog.probeFailed(target, suppressor.failed(key(), scrubbedReason));
    }

    /** One negative probe, in whichever repeat form the suppressor's streak makes it. */
    void probeFailed(String reason) {
        RemoteOutageGateLog.probeFailed(target, suppressor.failed(key(), reason));
    }

    /**
     * The one ERROR an over-long outage owes its operator; the watch's latch decides, so repeated
     * calls within one outage emit nothing. Parameters: when the outage began, when this probe
     * answered, and the current reason for the line's message. None null.
     */
    void sustainedOpen(Instant openedAt, Instant now, String reason) {
        if (sustainedOpenWatch.shouldFire(openedAt, now)) {
            RemoteOutageGateLog.sustainedOpen(target, sustainedOpenWatch.threshold(), reason);
        }
    }

    /**
     * The outage ended: one INFO recovery line naming how long the gate stayed open and how many
     * probes failed, and the streak cleared so the next outage reads as a first occurrence again.
     */
    void closed(Duration outage, int failedProbes) {
        RemoteOutageGateLog.closed(target, outage, failedProbes);
        suppressor.recovered(key());
    }

    private String key() {
        return "remote-outage:" + target;
    }
}
