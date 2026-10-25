package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.TakeResult;

/**
 * How an open {@link DaemonCondition} learns its precondition is met again (design D19 of
 * add-sandbox-base-image): the check that opened it, run once more against the daemon's own clone,
 * definition or image — never a second spelling of that check. The remote's probe is its {@code git
 * ls-remote} ({@link ConditionSubject#remote}); an operator precondition's is its startup check
 * ({@link ConditionSubject#precondition}).
 *
 * <p>Called with no lock held ({@code lock-scope.md}): a probe is a subprocess bounded by its own
 * deadline. A probe that cannot answer at all throws; the condition counts it as a failed probe
 * (the failure's words as the reason, its trace on the DEBUG line), grows its schedule and probes
 * again when that schedule says so — the throw never reaches the feed.
 *
 * <p>Implements FR15 of add-sandbox-base-image.
 */
@FunctionalInterface
interface ConditionProbe {

    /**
     * Runs the check once.
     *
     * @return the answer; never null
     */
    Answer probe();

    /** What one probe found. */
    sealed interface Answer {

        /** The precondition holds again: the condition closes. */
        record Met() implements Answer {}

        /**
         * The precondition still does not hold.
         *
         * @param reason the check's own message, already fit for the log; never null
         */
        record Unmet(String reason) implements Answer {}

        /**
         * The check could not judge this precondition because another one refused first (an image
         * probe meeting an unreachable runtime): this condition stays open, and the refusal opens
         * its own.
         *
         * @param refusal the other precondition's typed refusal; never null
         */
        record RefusedElsewhere(TakeResult.OperatorPreconditionUnmet refusal) implements Answer {}
    }
}
