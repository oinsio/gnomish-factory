package com.github.oinsio.gnomish.app.lease;

import java.util.Set;

/**
 * The outcome of one {@link LivenessOracle#evaluate()} call — never conflated (design D4 of
 * add-serve-sandbox-lifecycle): a tracker error yields {@link NoVerdict}, categorically distinct
 * from {@link Live} with an empty key set, so the sweep policy (task 3.x) can fail closed on the
 * former and act safely on the latter.
 *
 * <p>Implements FR3, NFR-R1 of add-serve-sandbox-lifecycle.
 */
public sealed interface LivenessVerdict {

    /**
     * The live environment-key set computed forward from this tick's listing: one sanitized key
     * per open task whose claim is not currently latched stale (FR3). Membership, not a reverse
     * key-to-task lookup, is how the sweep checks an object's ownership (design D1).
     *
     * @param environmentKeys the live keys; never null, may be empty
     */
    record Live(Set<String> environmentKeys) implements LivenessVerdict {}

    /** No listing is available this tick (a {@code listOpen} outage) — nothing tracked is touched. */
    record NoVerdict() implements LivenessVerdict {}
}
