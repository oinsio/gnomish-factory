package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;

/**
 * The production {@link Sleeper}: blocks the calling (virtual) thread via
 * {@link Thread#sleep(long)}. If interrupted mid-sleep, re-sets the thread's
 * interrupt flag rather than swallowing the interruption, per the port's explicit
 * "no checked exception on the contract, but don't swallow interruption" contract.
 *
 * <p>Lives in the composition root's module (design D20 of
 * supervise-daemon-loops-and-embed-dashboard): no other module can construct the real sleeper, so
 * every component outside {@code :bootstrap} waits on the sleeper half of the {@link
 * com.github.oinsio.gnomish.domain.engine.time.TimeEquipment} the root builds once in {@link
 * ManualRunConfiguration}.
 *
 * <p>Implements D10, M2 of add-manual-run; FR22 of supervise-daemon-loops-and-embed-dashboard.
 */
public final class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
