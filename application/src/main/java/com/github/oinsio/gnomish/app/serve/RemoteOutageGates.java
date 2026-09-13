package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Where a production {@link RemoteOutageGate} is built (tasks 7.3, 7.4 of add-base-ref-resolution):
 * the real {@link SystemClock}, an unseeded {@link Random}, and the installation's own bounds. Kept
 * out of the gate so that class holds the open/closed transition alone — the gate a spec builds on
 * virtual time and the one the daemon builds on the system clock are the same object, and only the
 * wiring differs.
 *
 * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution.
 */
public final class RemoteOutageGates {

    /** FR14's own default probe-interval ceiling, absent a configured one (task 7.3 scope note). */
    static final Duration DEFAULT_CAP = Duration.ofMinutes(10);

    private RemoteOutageGates() {}

    /**
     * The plain production gate: {@link #DEFAULT_CAP} and {@link RemoteOutageWiring#defaults()} — a
     * fresh daemon starts closed (FR14: "a restart forgets it") and logs to the console only.
     */
    public static RemoteOutageGate system(BaseRefGit baseRefGit, Path cloneDir, Duration idleInterval) {
        return new RemoteOutageGate(
                baseRefGit,
                cloneDir,
                new SystemClock(),
                new Random(),
                idleInterval,
                DEFAULT_CAP,
                RemoteOutageWiring.defaults());
    }

    /**
     * The gate wired for the composition root (task 7.4): {@code cap} and {@code
     * sustainedOpenThreshold} come from {@code factory.serve.remote-probe-interval-cap} / {@code
     * remote-sustained-open-threshold}; {@code onTransition}/{@code onClosedOutage} let the daemon's
     * snapshot writer and ledger appender learn about transitions without the gate knowing either
     * exists.
     */
    public static RemoteOutageGate system(
            BaseRefGit baseRefGit,
            Path cloneDir,
            Duration idleInterval,
            Duration cap,
            Duration sustainedOpenThreshold,
            Runnable onTransition,
            Consumer<RemoteOutageClosedOutage> onClosedOutage) {
        return new RemoteOutageGate(
                baseRefGit,
                cloneDir,
                new SystemClock(),
                new Random(),
                idleInterval,
                cap,
                new RemoteOutageWiring(
                        RemoteOutageReporter.DEFAULT_TARGET,
                        RepeatSuppressor.system(),
                        sustainedOpenThreshold,
                        onTransition,
                        onClosedOutage));
    }
}
