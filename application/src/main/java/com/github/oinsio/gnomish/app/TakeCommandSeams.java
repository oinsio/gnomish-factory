package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;

/**
 * The test-seam collaborators {@link TakeCommand} takes, defaulted for production wiring and
 * overridden selectively by individual specs: the {@link TimeEquipment} whose sleeper is the beat
 * {@link Sleeper} (task 6.1) and whose clock stamps the heartbeat (production wiring passes the composition root's one equipment, design D20 of
 * supervise-daemon-loops-and-embed-dashboard), the standing reaper's OWN {@link Sleeper}
 * (fix-reaper-idle-liveness FR5), the reaper's {@link MonotonicTime} (task 6.6), the {@link
 * TakeoverConfirmation} (task 6.2) and the {@link ServeProperties} used for batch mode's
 * concurrency limit N (task 6.2 of add-factory-serve, FR2). The time travels here rather than in
 * the command's constructor so the command could take the {@link ProjectScope} within the
 * parameter limit (FR3, FR10 of add-project-registry). Replaces the former telescoping {@code
 * of(...)} overloads of the command's factory, which design D7 of collapse-composition-roots
 * removed: start from {@link #defaults} and layer on only the seams a given spec cares about.
 *
 * <p>{@code reaperSleeper} defaults to the equipment's own sleeper rather than a second one; this is
 * harmless both for the production sleeper (stateless, reentrant) and for specs that don't care
 * about reaper timing. Only a spec driving the two threads' ticks separately needs {@link
 * #withReaperSleeper(Sleeper)} alongside {@link #withHeartbeatSleeper(Sleeper)}.
 */
record TakeCommandSeams(
        TimeEquipment time,
        Sleeper reaperSleeper,
        MonotonicTime heartbeatMonotonicTime,
        TakeoverConfirmation takeoverConfirmation,
        ServeProperties serveProperties) {

    /**
     * The production seams over {@code time}, the composition root's one time equipment (design
     * D17, D20 of supervise-daemon-loops-and-embed-dashboard): the beat and the standing reaper both
     * wait on its sleeper. Defaults batch mode's concurrency limit N to ServeProperties's own unset-slots
     * default (2, design D3); production wiring overrides via {@link #withServeProperties} with the
     * project's real, possibly-configured ServeProperties instead (task 6.2, FR2).
     *
     * <p>Implements FR18, FR22 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @param time the heartbeat's "now" and the beat's and the reaper's waiting; never null
     * @return the default seams on {@code time}; never null
     */
    static TakeCommandSeams defaults(TimeEquipment time) {
        return new TakeCommandSeams(
                time,
                time.sleeper(),
                new SystemMonotonicTime(),
                ConsoleTakeoverConfirmation.systemTty(),
                new ServeProperties(0, null, null, null, null, null, null, null, null, null));
    }

    TakeCommandSeams withHeartbeatSleeper(Sleeper heartbeatSleeper) {
        return new TakeCommandSeams(
                new TimeEquipment(time.clock(), heartbeatSleeper),
                reaperSleeper,
                heartbeatMonotonicTime,
                takeoverConfirmation,
                serveProperties);
    }

    TakeCommandSeams withReaperSleeper(Sleeper reaperSleeper) {
        return new TakeCommandSeams(time, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withHeartbeatMonotonicTime(MonotonicTime heartbeatMonotonicTime) {
        return new TakeCommandSeams(time, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withTakeoverConfirmation(TakeoverConfirmation takeoverConfirmation) {
        return new TakeCommandSeams(time, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }

    TakeCommandSeams withServeProperties(ServeProperties serveProperties) {
        return new TakeCommandSeams(time, reaperSleeper, heartbeatMonotonicTime, takeoverConfirmation, serveProperties);
    }
}
