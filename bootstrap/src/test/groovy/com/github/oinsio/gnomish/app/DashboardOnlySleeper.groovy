package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.fake.InterruptOnlySleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.MdcAwareThread
import com.github.oinsio.gnomish.status.DaemonComponent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import org.slf4j.MDC

/**
 * The daemon's one sleeper for a serve spec that runs the embedded dashboard on virtual time while
 * the rest of the daemon stands still: a wait framed as {@code component=dashboard} — the render
 * cadence and the restart backoff alike, both run inside the loop's frame — advances the shared
 * {@link VirtualClock} and returns, until the clock would pass {@code horizon}; every other wait,
 * and the dashboard's first wait past the horizon, parks until interrupted
 * ({@link InterruptOnlySleeper}), so no other loop turns over and a stop still cuts it short.
 *
 * <p>The frame is read from the {@code component} MDC key every supervised loop sets
 * (logging.md), the observable name of the thread — not a thread name or a private field.
 *
 * <p>Test fixture. Implements FR10, NFR-P1, NFR-R1 of supervise-daemon-loops-and-embed-dashboard.
 */
final class DashboardOnlySleeper implements Sleeper {

    private final VirtualClock clock
    private final Instant horizon
    private final Sleeper parked = new InterruptOnlySleeper()

    /** Counted down when the dashboard's waits first reach the horizon. */
    final CountDownLatch horizonReached = new CountDownLatch(1)

    DashboardOnlySleeper(VirtualClock clock, Instant horizon) {
        this.clock = clock
        this.horizon = horizon
    }

    @Override
    void sleep(Duration duration) {
        if (MDC.get(MdcAwareThread.COMPONENT_KEY) == DaemonComponent.DASHBOARD.key()) {
            // One dashboard worker at a time: a respawn waits its backoff on the dying thread.
            if (!clock.instant().plus(duration).isAfter(horizon)) {
                clock.advance(duration)
                return
            }
            horizonReached.countDown()
        }
        parked.sleep(duration)
    }
}
