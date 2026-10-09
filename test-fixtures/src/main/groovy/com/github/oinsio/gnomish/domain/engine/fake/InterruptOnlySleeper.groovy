package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * A {@link Sleeper} that blocks until the sleeping thread is interrupted, then returns with the
 * interrupt flag set — the interrupt contract the production sleeper keeps (it lives in
 * {@code :bootstrap}, out of reach of the other modules' specs, design D20 of
 * supervise-daemon-loops-and-embed-dashboard), without any real time passing: a spec asserting
 * that a stop or an interrupt cuts a wait short needs a wait only an interrupt can end, not a
 * clock.
 *
 * <p>Test fake; not production code, never PIT-mutated.
 */
class InterruptOnlySleeper implements Sleeper {

    @Override
    void sleep(Duration duration) {
        try {
            new CountDownLatch(1).await()
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }
}
