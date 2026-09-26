package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
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
 * <p>Also the one owner of the gate's decoration of a serve slot's git ({@link #signaling}): the
 * decorator is applied by the composition root, never by the slot it equips (D6 of
 * introduce-slot-wiring).
 *
 * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution; FR4 of introduce-slot-wiring.
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

    /**
     * The task git a serve slot works with: {@code git} with its base-ref port wrapped in {@link
     * RemoteOutageSignalingBaseRefGit}, so every claim-time base read reports to {@code gate} at
     * the moment it returns (FR14 of add-base-ref-resolution). The only construction of that
     * decorator: the serve assembly point fills the slot wiring's {@code git} from here, and the
     * slot itself never re-decorates (D6 of introduce-slot-wiring).
     *
     * @param git the undecorated task git; never null
     * @param gate the daemon's one remote outage gate — the SAME instance its feed automaton
     *     consults; never null
     * @return a copy of {@code git} differing only in its base-ref port; never null
     */
    public static TaskGit signaling(TaskGit git, RemoteOutageGate gate) {
        return git.withBaseRefs(new RemoteOutageSignalingBaseRefGit(git.baseRefs(), gate));
    }
}
