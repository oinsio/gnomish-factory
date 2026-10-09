package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Where a production {@link RemoteOutageGate} is built (tasks 7.3, 7.4 of add-base-ref-resolution):
 * the time source the serve assembly holds, an unseeded {@link Random}, and the installation's own
 * bounds. Kept
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

    private RemoteOutageGates() {}

    /**
     * The gate wired for the serve daemon (task 7.4 of add-base-ref-resolution): the idle interval,
     * the probe-interval ceiling and the sustained-open threshold come from {@code factory.serve}
     * ({@code idle-poll-interval}, {@code remote-probe-interval-cap}, {@code
     * remote-sustained-open-threshold}); {@code onTransition}/{@code onClosedOutage} let the
     * daemon's snapshot writer and ledger appender learn about transitions without the gate knowing
     * either exists. The gate and its failed-probe suppressor both measure on {@code source}, the
     * composition root's one time source (design D17 of supervise-daemon-loops-and-embed-dashboard).
     *
     * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution; FR18 of
     * supervise-daemon-loops-and-embed-dashboard.
     */
    public static RemoteOutageGate forServe(
            BaseRefGit baseRefGit,
            Path cloneDir,
            ServeProperties serveProperties,
            InstantSource source,
            Runnable onTransition,
            Consumer<RemoteOutageClosedOutage> onClosedOutage) {
        return new RemoteOutageGate(
                baseRefGit,
                cloneDir,
                source,
                new Random(),
                serveProperties.idlePollInterval(),
                serveProperties.remoteProbeIntervalCap(),
                new RemoteOutageWiring(
                        RemoteOutageReporter.DEFAULT_TARGET,
                        RepeatSuppressor.withDefaultRollUp(source),
                        serveProperties.remoteSustainedOpenThreshold(),
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
