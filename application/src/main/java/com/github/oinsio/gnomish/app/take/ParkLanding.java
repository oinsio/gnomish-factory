package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.TakeOrder;
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import org.slf4j.Logger;

/**
 * Whether a recovered park's tracker write already landed, read from the task's history: the park
 * landed iff the task's <b>tenure boundary</b> — the thread's last boundary marker, whatever claims
 * follow it — is {@code PARK}. {@code STALE_CLAIM_REMOVED} or none means the write never reached the
 * tracker (the reaper returned the dead claim); a {@code FINISH} or {@code ABORT} boundary is not
 * this park either, since a park has landed only when the boundary is {@code PARK}. Never the
 * current state: every pickup runs after its own claim, which has already made it {@code Working}.
 *
 * <p>Two callers, one reading: {@link GuardedPark#observeAtTarget}, the protocol's probe, and
 * {@link #confirmFromHistory}, which a pickup's route asks before it decides between re-driving the
 * park and consuming the human's reply (design D6).
 *
 * <p>Implements FR2, NFR-O1 of fix-terminal-receipt-convergence.
 */
public final class ParkLanding {

    private ParkLanding() {}

    /**
     * Confirms a recovered park from history: a landed park records only its owed receipt and
     * answers true, so the caller continues with the human's reply or approval. Otherwise nothing is
     * recorded and the caller re-drives the park through {@link GuardedPark#attempt}, whose own probe
     * makes the final decision and writes the WARN if the tracker still cannot be asked — so a
     * failure here is DEBUG only.
     *
     * @param order the just-claimed task's take order: its tracker and identity; never null
     * @param receipt clears the branch's pending marker; run only when the park is confirmed
     * @param log the caller's logger, so log lines are attributed to the calling class; never null
     * @return true when the park was confirmed and its receipt recorded
     */
    public static boolean confirmFromHistory(TakeOrder order, Runnable receipt, Logger log) {
        boolean landed;
        try {
            landed = landed(order, log, "park");
        } catch (RuntimeException e) {
            log.debug(
                    "park of {} unconfirmed; the re-drive probes again",
                    order.ref().id(),
                    e);
            return false;
        }
        if (landed) {
            receipt.run();
        }
        return landed;
    }

    /**
     * The one reading of the tenure boundary for a park; one DEBUG line when it confirms (NFR-O1).
     *
     * @throws RuntimeException when the tracker cannot be asked; each caller decides how to log it
     */
    static boolean landed(TakeOrder order, Logger log, String kind) {
        boolean landed = order.tracker().fetchTask(order.ref()).tenureBoundary() == BoundaryKind.PARK;
        if (landed) {
            log.debug(
                    "{} of {} confirmed from its tenure history",
                    kind,
                    order.ref().id());
        }
        return landed;
    }
}
