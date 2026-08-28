package com.github.oinsio.gnomish.app.terminal;

/**
 * What a flow's probe saw at the target of an external effect whose intent is durable but whose
 * receipt is not: the effect is already there, it is demonstrably not there, or the target could
 * not be asked (FR10).
 *
 * <p>{@link #UNDETERMINED} deliberately re-drives rather than skips: every factory-authored tracker
 * write is a find-then-upsert (FR11), so a re-drive of an effect that did land updates it in place,
 * while skipping one that never landed loses the transition.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
public enum EffectObservation {

    /** The target already carries the effect: the intent's write happened, only its receipt did not. */
    LANDED,

    /** The target does not carry the effect: the kill window closed before the write. */
    ABSENT,

    /** The target could not be asked, so the safe reading is "not there". */
    UNDETERMINED
}
