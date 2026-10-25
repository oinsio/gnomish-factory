package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import java.time.Duration;

/**
 * The lines one {@link DaemonCondition} writes (design D19 of add-sandbox-base-image, "One failure,
 * one log"): one WARN when it opens, DEBUG for every failed probe in between, one ERROR past the
 * sustained-open threshold, one INFO when it closes. The remote keeps the lines it always had
 * ({@link RemoteOutageGateLog}); every operator precondition writes its own codes ({@link
 * PreconditionConditionLog}), so a grep or an alert names the condition by its code.
 *
 * <p>Implements FR15, NFR-O2 of add-sandbox-base-image; NFR-O1 of add-base-ref-resolution.
 */
interface ConditionLog {

    /** The one WARN of a closed-to-open transition, naming the cause and its fix. */
    void opened(String reason);

    /** The DEBUG line of one failed probe, in whichever repeat form the streak made it. */
    void probeFailed(RepeatOccurrence occurrence);

    /**
     * The DEBUG line of one probe that threw instead of answering: the same streak line, with the
     * failure's trace on the streak's first occurrence (logging.md: no swallowed failure without a
     * trace) and none on a repeat or a roll-up, which the first already carried.
     */
    void probeUnanswered(RepeatOccurrence occurrence, RuntimeException cause);

    /** The one ERROR of a condition open longer than {@code threshold}. */
    void sustainedOpen(Duration threshold, String lastError);

    /** The one INFO recovery line, with the episode's duration and failed probe count. */
    void closed(Duration episode, int failedProbes);

    /**
     * The lines of the condition on {@code kind}.
     *
     * @param kind the condition's precondition; never null
     * @param target the identity the remote's lines name; never blank
     * @return the condition's lines; never null
     */
    static ConditionLog forKind(DaemonConditionKind kind, String target) {
        return kind == DaemonConditionKind.REMOTE ? new RemoteLines(target) : new PreconditionConditionLog(kind);
    }

    /** The remote's lines, unchanged from the remote outage gate's. */
    record RemoteLines(String target) implements ConditionLog {

        @Override
        public void opened(String reason) {
            RemoteOutageGateLog.opened(target, reason);
        }

        @Override
        public void probeFailed(RepeatOccurrence occurrence) {
            RemoteOutageGateLog.probeFailed(target, occurrence);
        }

        @Override
        public void probeUnanswered(RepeatOccurrence occurrence, RuntimeException cause) {
            RemoteOutageGateLog.probeUnanswered(target, occurrence, cause);
        }

        @Override
        public void sustainedOpen(Duration threshold, String lastError) {
            RemoteOutageGateLog.sustainedOpen(target, threshold, lastError);
        }

        @Override
        public void closed(Duration episode, int failedProbes) {
            RemoteOutageGateLog.closed(target, episode, failedProbes);
        }
    }
}
