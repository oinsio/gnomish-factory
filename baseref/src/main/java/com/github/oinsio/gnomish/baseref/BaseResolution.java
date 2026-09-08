package com.github.oinsio.gnomish.baseref;

import java.util.List;
import java.util.Objects;

/**
 * What {@link BaseRefResolver} answers: a base, or a refusal to guess at one.
 *
 * <p>The refusal is a value rather than an exception because it is an ordinary outcome the caller
 * routes — a park with a report for the two designator causes, an infrastructure classification for
 * a missing default branch — and because a pure policy that threw would make its own callers decide
 * by catching.
 *
 * <p>Implements FR4, FR10 of add-base-ref-resolution.
 */
public sealed interface BaseResolution {

    /**
     * A base was determined.
     *
     * @param decision the ref, the tier that produced it, and the reason
     */
    record Resolved(BaseDecision decision) implements BaseResolution {

        /** A resolved outcome without a decision would be a third state nothing handles. */
        public Resolved {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * No base could be determined, and the policy refuses to substitute one. Every field is report
     * material: the human who fixes this reads the values found and the allowed bases that rejected them.
     *
     * @param cause which of the three causes it is — what the caller routes on
     * @param values the designator values found on the task, in the order the adapter reported them;
     *     empty when the cause is not a designator one
     * @param reason one sentence naming what was found and what the configuration allows
     */
    record Underdetermined(UnderdeterminedCause cause, List<String> values, String reason) implements BaseResolution {

        /** Copies the values, so a report built later reads what resolution actually saw. */
        public Underdetermined {
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(reason, "reason");
            values = List.copyOf(values);
        }
    }
}
