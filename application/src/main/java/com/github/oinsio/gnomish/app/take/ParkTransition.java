package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;

/**
 * The branch-side steps of one park, in the two ways a park is reached (FR10, design D5 of
 * harden-task-branch-contract): fresh — the outcome commit has yet to be written — or recovered, an
 * intent already durable on the branch whose tracker write never confirmed.
 *
 * <p>The distinction is the protocol's, not a caller's convenience: only a fresh park records its
 * intent, and only a recovered one probes the tracker before re-driving the write.
 *
 * <p>Implements FR10 of harden-task-branch-contract.
 */
public sealed interface ParkTransition {

    /**
     * Records the park's durable intent — the outcome commit carrying the pending marker — and
     * delivers it to origin, returning what the delivery fence saw.
     */
    @FunctionalInterface
    interface ParkIntent {

        /**
         * @return the delivery fence's verdict on the recorded park; never null
         */
        ParkDeliveryVerdict record();
    }

    /**
     * The receipt: what marks the park's tracker write as landed, so a later pickup reads the park
     * as settled instead of orphaned.
     *
     * @return the receipt step; never null
     */
    Runnable receipt();

    /**
     * A park happening now.
     *
     * @param intent records the outcome commit and delivers it to origin
     * @param receipt clears the durable pending marker once the park lands
     */
    record Fresh(ParkIntent intent, Runnable receipt) implements ParkTransition {}

    /**
     * A park whose intent is already on the branch and whose tracker write is still owed.
     *
     * @param verdict what the caller's delivery fence saw for the already-recorded park
     * @param receipt clears the durable pending marker once the park lands
     */
    record Recovered(ParkDeliveryVerdict verdict, Runnable receipt) implements ParkTransition {}
}
