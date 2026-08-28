package com.github.oinsio.gnomish.app.terminal;

/**
 * How a run of the intent→effect→receipt protocol ended: the effect is at the target and the
 * receipt is recorded, the effect was already there and only the receipt was owed, or the effect
 * could not be confirmed and the intent stays outstanding for the next pickup (FR10).
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
public enum EffectDelivery {

    /** This run delivered the effect and recorded its receipt. */
    CONFIRMED,

    /** The probe found the effect already at the target; this run recorded only the owed receipt. */
    ALREADY_LANDED,

    /**
     * The effect could not be confirmed within its own bound. No receipt is recorded, so the
     * durable intent stays outstanding and the next pickup re-drives it.
     */
    UNCONFIRMED;

    /**
     * Whether the target carries the effect after this run — true for both the delivery this run
     * made and the one it found already made.
     *
     * @return {@code true} unless the effect is still owed
     */
    public boolean settled() {
        return this != UNCONFIRMED;
    }
}
