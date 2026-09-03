package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import org.slf4j.Logger;

/**
 * What a mid-round poll needs in order to say whose round it is polling and where that round's
 * failure streaks are kept — the parameter object both listeners take in place of the three
 * arguments they only ever hand straight to {@link MidRoundPollLog} (process-invariants.md's
 * parameter-count limit).
 *
 * <p>It also retires a transposition hazard the limit's rule names in its own right: {@code
 * taskId} and {@code branch} are two adjacent {@code String}s, and a caller that swapped them
 * would produce a listener that logs one task's id against another's branch and namespaces its
 * streaks by the wrong subject — all of it silent, since both values are well-formed strings. Here
 * they are named components, which a swap cannot survive unnoticed.
 *
 * <p>The suppressor is deliberately <em>not</em> created here: it must outlive the round, because
 * an environment that cannot be harvested is one fault whether it spans polls of one round or
 * rounds of one task, so the caller that owns the task owns the suppressor and each round's
 * context borrows it.
 *
 * <p>Implements FR4 of harden-logging-observability.
 *
 * @param taskId the task whose round is polling, for log context
 * @param branch the task branch the poll watches; namespaces the streak keys
 * @param suppressor the edge-logging owner of the poll's failure streaks; outlives the round
 */
public record MidRoundPollContext(String taskId, String branch, RepeatSuppressor suppressor) {

    /**
     * The poll log this round reports through.
     *
     * @param log the listener's own logger, so the lines are attributed to the polling class
     * @return the poll log; never null
     */
    MidRoundPollLog logTo(Logger log) {
        return new MidRoundPollLog(log, suppressor, taskId, branch);
    }
}
