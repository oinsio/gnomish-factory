package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The edge logging of one round's mid-round poll ({@link MidRoundHarvestListener} in a sandboxed
 * round, {@link MidRoundPushListener} in a host one), owned here so the listeners hold the polling
 * decisions and this holds what the operator is told about them.
 *
 * <p>The poll runs once per agent progress event, so a failure that persists would otherwise cost
 * one WARN per event for the whole round: every failure reports to a {@link RepeatSuppressor} and
 * only the edges are logged — first occurrence and periodic counted roll-ups at WARN, the polls in
 * between at DEBUG, one INFO when the subject works again (FR4 of harden-logging-observability).
 *
 * <p>Two subjects, suppressed independently: the <b>harvest</b> (mid-round commits stop being
 * mirrored out of the environment) and the <b>tip resolution</b> (the poll cannot see where the
 * branch is, so it observes nothing at all — FR13). The host-mode poll has no environment to
 * harvest and reports only the second.
 *
 * <p>Implements FR4, FR13 of harden-logging-observability.
 *
 * @param log the listener's own logger, so lines are attributed to the polling class
 * @param suppressor the edge-logging owner for this round's failure streaks
 * @param taskId the task whose round is polling, for log context
 * @param branch the task branch being watched; namespaces the streak keys
 */
record MidRoundPollLog(Logger log, RepeatSuppressor suppressor, String taskId, String branch) {

    /** The suppressor subjects; each is namespaced by branch so rounds never share a streak. */
    enum Subject {
        HARVEST("mid-round harvest"),
        TIP("mid-round tip resolution");

        private final String label;

        Subject(String label) {
            this.label = label;
        }
    }

    /**
     * Reports one failed poll of {@code subject} and logs whichever edge the suppressor returns.
     *
     * @param subject what failed
     * @param reason the failure's own words; sanitized here, since git and container output reach
     *     this line unfiltered
     * @param failure the throwable behind the failure, rendered as the trailing argument on every
     *     form so the diagnosis survives the suppression; null where the failure is a git exit
     *     status rather than a thrown fault, in which case {@code reason} carries the evidence
     */
    void failed(Subject subject, String reason, @Nullable Throwable failure) {
        String clean = LogText.forLog(reason);
        switch (suppressor.failed(key(subject), clean)) {
            case RepeatOccurrence.First ignored ->
                log.warn(
                        OperatorEvent.MID_ROUND_POLL_SKIPPED.head() + "{} skipped: taskId={}, branch={}, reason={}",
                        subject.label,
                        taskId,
                        branch,
                        clean,
                        failure);
            case RepeatOccurrence.Repeat repeat ->
                log.debug(
                        "{} skipped again ({}x): taskId={}, branch={}, reason={}",
                        subject.label,
                        repeat.count(),
                        taskId,
                        branch,
                        clean,
                        failure);
            case RepeatOccurrence.RollUp rollUp ->
                log.warn(
                        OperatorEvent.MID_ROUND_POLL_SKIPPED_ROLLUP.head()
                                + "{} skipped {}x over {}: taskId={}, branch={}, reason={}",
                        subject.label,
                        rollUp.count(),
                        rollUp.elapsed(),
                        taskId,
                        branch,
                        clean,
                        failure);
        }
    }

    /** One INFO when {@code subject} works again, so the last word on it is not the failure. */
    void recovered(Subject subject) {
        suppressor
                .recovered(key(subject))
                .ifPresent(recovery -> log.info(
                        "{} recovered after {} failure(s) over {}: taskId={}, branch={}, last reason={}",
                        subject.label,
                        recovery.occurrences(),
                        recovery.outage(),
                        taskId,
                        branch,
                        recovery.reason()));
    }

    /** Namespaces a streak key: the subject is this round's branch inside its environment. */
    private String key(Subject subject) {
        return subject.name().toLowerCase(Locale.ROOT) + ":" + branch;
    }
}
