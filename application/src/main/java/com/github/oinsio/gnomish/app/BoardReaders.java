package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;

/**
 * The narrow face of {@link TrackerWiring} the serve runtime assembly builds its embedded board
 * client through (design D11 of supervise-daemon-loops-and-embed-dashboard): one read-only {@link
 * Tracker} from the configuration and adapter factory {@code serve} already bound from origin's
 * default branch. The assembly takes this interface rather than the whole wiring, so it holds
 * nothing through which the credential seam behind the wiring could be reached (NFR-S1: use of
 * the secrets is granted, possession is not), and it takes no {@code Path}, so it cannot read a
 * second configuration from the checkout.
 *
 * <p>Implements FR10, NFR-S1 of supervise-daemon-loops-and-embed-dashboard.
 */
interface BoardReaders {

    /**
     * Builds a separate, read-only adapter instance for the board (the bulkhead of design D11):
     * the same factory and configuration as {@code bound}, under {@code readerId} — never the
     * daemon's claiming identity — with no tenure record, and neither health-wrapped nor
     * epoch-stamped, so its failures and latency stay out of the daemon's tracker health.
     *
     * @param bound the binding {@code serve} made from origin's default branch; never null
     * @param readerId the reader's own minted instance id; never null
     * @return the reader, as the adapter factory built it
     */
    Tracker boardReader(BoundTracker bound, InstanceId readerId);
}
