package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * How a round ended, as its dialect reads the exit code and the events (FR2, FR8 of
 * extract-agent-round-runner). The runner maps it to the contract's status: {@link Completed} to
 * {@code completed}, {@link LimitReached} to {@code failed/limit} with the escalation report {@code
 * budget exhausted: <bound>}, {@link Broken} to {@code failed/infrastructure} with the exit code and
 * the stderr tail beside the reason.
 *
 * <p>Implements FR2, FR8 of extract-agent-round-runner.
 */
public sealed interface RoundStatus {

    /** The round ran to its end. */
    record Completed() implements RoundStatus {}

    /**
     * The CLI stopped the round on a turn or budget bound.
     *
     * @param bound the bound that stopped it, as the escalation report names it; never blank
     */
    record LimitReached(String bound) implements RoundStatus {

        public LimitReached {
            requireNonBlank(bound);
        }
    }

    /**
     * The round ended without its result: a non-zero exit with no result event, or a result the
     * dialect reads as an error.
     *
     * @param reason why, as the dialect states it; never null
     */
    record Broken(UntrustedText reason) implements RoundStatus {}

    /**
     * Refuses a blank bound: an escalation report must name what was exhausted. Kept as an explicit
     * static method rather than inline in the compact constructor: PIT's record filter suppresses
     * all mutations inside a record's canonical constructor.
     */
    private static void requireNonBlank(String bound) {
        if (bound.isBlank()) {
            throw new IllegalArgumentException("RoundStatus.LimitReached.bound must not be blank");
        }
    }
}
