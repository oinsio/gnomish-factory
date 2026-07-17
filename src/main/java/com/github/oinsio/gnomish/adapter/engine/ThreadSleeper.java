package com.github.oinsio.gnomish.adapter.engine;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;

/**
 * The production {@link Sleeper}: blocks the calling (virtual) thread via
 * {@link Thread#sleep(long)}. If interrupted mid-sleep, re-sets the thread's
 * interrupt flag rather than swallowing the interruption, per the port's explicit
 * "no checked exception on the contract, but don't swallow interruption" contract.
 *
 * <p>Implements D10, M2 of add-manual-run.
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
