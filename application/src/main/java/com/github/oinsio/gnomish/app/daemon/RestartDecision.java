package com.github.oinsio.gnomish.app.daemon;

import java.time.Duration;

/**
 * What a {@link RestartPolicy} decides when a supervised loop's thread dies (design D5 of
 * supervise-daemon-loops-and-embed-dashboard): respawn after a backoff, or give up for good.
 *
 * <p>Implements FR3 of supervise-daemon-loops-and-embed-dashboard.
 */
sealed interface RestartDecision {

    /**
     * Respawn the worker once {@code backoff} has elapsed.
     *
     * @param backoff the wait before the respawn; never null
     * @param restartCount the lifetime restart count, this restart included
     */
    record Respawn(Duration backoff, int restartCount) implements RestartDecision {}

    /**
     * Do not respawn: the policy's restart budget within its window is spent.
     *
     * @param restartCount the lifetime restart count, which this death does not raise
     * @param maxRestarts the restarts the policy allows within {@code window}
     * @param window the period the restarts are counted over; never null
     */
    record GiveUp(int restartCount, int maxRestarts, Duration window) implements RestartDecision {}
}
