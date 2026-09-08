package com.github.oinsio.gnomish.baseref;

/**
 * Whether a human is standing at the clone. The distinction exists for exactly one reason: only a
 * manual invocation may fall through to the clone's local HEAD.
 *
 * <p>An autonomous run has no one to notice that the clone is three weeks behind, which is the
 * staleness this capability exists to end; a manual {@code gnomish run} is the pipeline author's
 * inner loop, where "whatever I have checked out, uncommitted edits included" is the whole point.
 *
 * <p>Implements FR4, FR5, FR8 of add-base-ref-resolution.
 */
public enum ResolutionMode {

    /** {@code gnomish run} started by a human at a terminal. May fall through to the local HEAD. */
    MANUAL,

    /** {@code gnomish take} and {@code gnomish serve}. Never falls through to the local HEAD. */
    AUTONOMOUS
}
