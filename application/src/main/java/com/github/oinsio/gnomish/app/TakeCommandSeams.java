package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import java.time.InstantSource;

/**
 * The test-seam collaborators {@link TakeCommand} takes, defaulted for production wiring and
 * overridden selectively by individual specs: the beat {@link Sleeper} (task 6.1), the standing
 * reaper's OWN {@link Sleeper} (fix-reaper-idle-liveness FR5), the reaper's {@link MonotonicTime}
 * (task 6.6), the {@link TakeoverConfirmation} (task 6.2), the {@link ServeProperties} used for
 * batch mode's concurrency limit N (task 6.2 of add-factory-serve, FR2), and the {@link InstantSource} that
 * supplies "now" for bare-mode backoff, takeover and the heartbeat (production wiring passes its {@code
 * instantSource} bean; moved here from the command's constructor so the command could take the
 * {@link ProjectScope} within the parameter limit, FR3, FR10 of add-project-registry). Replaces the former
 * telescoping {@code of(...)} overloads of the command's factory, which design D7 of
 * collapse-composition-roots removed: start from {@link #defaults} and layer on only the seams a
 * given spec cares about.
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
        ServeProperties serveProperties,
        InstantSource clock) {

    /**
     * The production seams over {@code clock}, the composition root's one time source (design D17
     * of supervise-daemon-loops-and-embed-dashboard). Defaults batch mode's concurrency limit N to
     * ServeProperties's own unset-slots default (2, design D3); production wiring overrides via
     * {@link #withServeProperties} with the project's real, possibly-configured ServeProperties
     * instead (task 6.2, FR2).
     *
     * <p>Implements FR18 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @param clock supplies "now" for bare-mode backoff, takeover and the heartbeat; never null
     * @return the default seams on {@code clock}; never null
     */
    static TakeCommandSeams defaults(InstantSource clock) {
        return new TakeCommandSeams(
                new ThreadSleeper(),
                new ThreadSleeper(),
                new SystemMonotonicTime(),
                ConsoleTakeoverConfirmation.systemTty(),
                new ServeProperties(0, null, null, null, null, null, null, null, null),
                clock);
    }

    TakeCommandSeams withHeartbeatSleeper(Sleeper heartbeatSleeper) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }

    TakeCommandSeams withReaperSleeper(Sleeper reaperSleeper) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }

    TakeCommandSeams withHeartbeatMonotonicTime(MonotonicTime heartbeatMonotonicTime) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }

    TakeCommandSeams withTakeoverConfirmation(TakeoverConfirmation takeoverConfirmation) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }

    TakeCommandSeams withServeProperties(ServeProperties serveProperties) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }

    TakeCommandSeams withClock(InstantSource clock) {
        return new TakeCommandSeams(
                heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties, clock);
    }
}
