package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;

/**
 * The test-seam collaborators {@link TakeCommandFactory} defaults for production wiring and
 * individual specs override selectively: the beat {@link Sleeper} (task 6.1), the standing
 * reaper's OWN {@link Sleeper} (fix-reaper-idle-liveness FR5), the reaper's {@link MonotonicTime}
 * (task 6.6), the {@link TakeoverConfirmation} (task 6.2), and the {@link ServeProperties} used for
 * batch mode's concurrency limit N (task 6.2 of add-factory-serve, FR2). Replaces the former
 * telescoping {@code of(...)} overloads on {@link TakeCommandFactory} one parameter object at a
 * time: start from {@link #DEFAULTS} and layer on only the seams a given spec cares about.
 *
 * <p>{@code reaperSleeper} defaults independently of {@code heartbeatSleeper} rather than mirroring
 * it; this is harmless both for the production {@code ThreadSleeper} (stateless, reentrant) and for
 * specs that don't care about reaper timing. Only a spec driving the two threads' ticks separately
 * needs {@link #withReaperSleeper(Sleeper)} alongside {@link #withHeartbeatSleeper(Sleeper)}.
 */
record TakeCommandSeams(
        Sleeper heartbeatSleeper,
        Sleeper reaperSleeper,
        MonotonicTime heartbeatMonotonicTime,
        TakeoverConfirmation takeoverConfirmation,
        ServeProperties serveProperties) {

    // Defaults batch mode's concurrency limit N to ServeProperties's own unset-slots default (2,
    // design D3); production wiring (ManualRunRunner) overrides via withServeProperties with the
    // project's real, possibly-configured ServeProperties instead (task 6.2, FR2).
    static final TakeCommandSeams DEFAULTS = new TakeCommandSeams(
            new ThreadSleeper(),
            new ThreadSleeper(),
            new SystemMonotonicTime(),
            ConsoleTakeoverConfirmation.systemTty(),
            new ServeProperties(0, null, null, null, null, null));

    TakeCommandSeams withHeartbeatSleeper(Sleeper heartbeatSleeper) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withReaperSleeper(Sleeper reaperSleeper) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withHeartbeatMonotonicTime(MonotonicTime heartbeatMonotonicTime) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withTakeoverConfirmation(TakeoverConfirmation takeoverConfirmation) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withServeProperties(ServeProperties serveProperties) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }
}
