package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Random;

/**
 * Where a production {@link DaemonConditions} set is built (tasks 7.3, 7.4 of add-base-ref-resolution):
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

    /** The operator preconditions a running daemon holds a condition on, in the order a slot meets them. */
    private static final List<DaemonConditionKind> PRECONDITIONS = List.of(
            DaemonConditionKind.CLONE_REF_FORMAT,
            DaemonConditionKind.CONTAINER_RUNTIME,
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.BOX_GIT_FLOOR);

    /**
     * The conditions wired for the serve daemon (task 7.4 of add-base-ref-resolution; design D19 of
     * add-sandbox-base-image): the remote, then one condition per operator precondition, each
     * probing with its startup check from {@code rechecks}. The idle interval, the probe-interval
     * ceiling and the sustained-open threshold come from {@code factory.serve} ({@code
     * idle-poll-interval}, {@code remote-probe-interval-cap}, {@code remote-sustained-open-threshold})
     * for every condition; {@code onTransition}/{@code onClosedOutage} let the daemon's snapshot
     * writer and ledger appender learn about transitions without a condition knowing either exists
     * (one {@code daemonCondition} ledger line per closed episode, of every condition). Every
     * condition and its failed-probe suppressor measure on {@code source}, the composition root's
     * one time source (design D17 of supervise-daemon-loops-and-embed-dashboard).
     *
     * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution; FR18 of
     * supervise-daemon-loops-and-embed-dashboard; FR15, FR16 of add-sandbox-base-image.
     */
    public static DaemonConditions forServe(
            BaseRefGit baseRefGit,
            Path cloneDir,
            ServeProperties serveProperties,
            InstantSource source,
            DirtyNotifier onTransition,
            RemoteOutageLedgerSink onClosedOutage,
            PreconditionRecheck rechecks) {
        DaemonCondition remote = condition(
                ConditionSubject.remote(baseRefGit, cloneDir),
                serveProperties,
                source,
                new RemoteOutageWiring(
                        RemoteOutageReporter.DEFAULT_TARGET,
                        RepeatSuppressor.withDefaultRollUp(source),
                        serveProperties.remoteSustainedOpenThreshold(),
                        onTransition,
                        onClosedOutage));
        List<DaemonCondition> preconditions = PRECONDITIONS.stream()
                .map(kind -> condition(
                        ConditionSubject.precondition(kind, rechecks),
                        serveProperties,
                        source,
                        new RemoteOutageWiring(
                                kind.name(),
                                RepeatSuppressor.withDefaultRollUp(source),
                                serveProperties.remoteSustainedOpenThreshold(),
                                onTransition,
                                onClosedOutage)))
                .toList();
        return new DaemonConditions(remote, preconditions);
    }

    private static DaemonCondition condition(
            ConditionSubject subject,
            ServeProperties serveProperties,
            InstantSource source,
            RemoteOutageWiring wiring) {
        return new DaemonCondition(
                subject,
                source,
                new Random(),
                serveProperties.idlePollInterval(),
                serveProperties.remoteProbeIntervalCap(),
                wiring);
    }

    /**
     * The task git a serve slot works with: {@code git} with its base-ref port wrapped in {@link
     * RemoteOutageSignalingBaseRefGit} and its branch port in {@link
     * RemoteOutageSignalingTaskBranchGit}, so every claim-time read of origin — the task-branch
     * lookup, the fresh-claim base refresh and the resume rebind — reports to the remote condition at the
     * moment it returns (FR14 of add-base-ref-resolution; finding F88 of tighten-spec-contract).
     * The only construction of either decorator: the serve assembly point fills the slot wiring's
     * {@code git} from here, and the slot itself never re-decorates (D6 of introduce-slot-wiring).
     *
     * @param git the undecorated task git; never null
     * @param conditions the daemon's conditions — the SAME set its feed automaton consults; the
     *     decorations signal its remote; never null
     * @return a copy of {@code git} differing only in its base-ref and branch ports; never null
     */
    public static TaskGit signaling(TaskGit git, DaemonConditions conditions) {
        DaemonCondition remote = conditions.remote();
        return git.withBaseRefs(new RemoteOutageSignalingBaseRefGit(git.baseRefs(), remote))
                .withBranches(new RemoteOutageSignalingTaskBranchGit(git.branches(), remote));
    }
}
