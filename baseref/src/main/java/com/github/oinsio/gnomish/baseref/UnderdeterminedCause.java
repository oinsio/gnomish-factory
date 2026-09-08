package com.github.oinsio.gnomish.baseref;

/**
 * Why resolution could not name a base. The three causes exist as separate constants because the
 * caller routes them differently, not merely to phrase a report: the two designator causes are
 * quality-of-input escalations that park the task for a human without burning a stage attempt,
 * while a missing default branch is a fact the caller obtained (or failed to obtain) from the
 * remote and classifies under its own infrastructure rules.
 *
 * <p>Implements FR4, FR5, FR9 of add-base-ref-resolution.
 */
public enum UnderdeterminedCause {

    /**
     * The task named a base no allowed-base pattern accepts — including a name that is not a ref
     * name.
     */
    DESIGNATOR_NOT_ALLOWED,

    /** The task named more than one base. Resolution never picks one of them. */
    DESIGNATOR_CONFLICT,

    /**
     * Nothing named a base and the repository default branch is unknown — no configured default and
     * no answer from the remote. In an autonomous path the caller reports the remote's silence as an
     * infrastructure failure; falling back to the clone's local HEAD is the manual tier's privilege
     * alone.
     */
    NO_DEFAULT_BRANCH
}
