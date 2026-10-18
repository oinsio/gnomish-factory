package com.github.oinsio.gnomish.subprocess;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live group registry (design D19 of fix-terminal-receipt-convergence, fourth amendment): the
 * one record of the host process groups that may still have members — the group ids the launch
 * owner started and no sweep has found empty yet. The shutdown step reads it to sweep what no waiter
 * reached: a child in its own session receives nothing from the terminal, and in {@code serve} a
 * waiter sweeps only once its post-exit bound has passed, so an orphan the descendant snapshot
 * misses would otherwise outlive the factory.
 *
 * <p>Two writers, both in this module and nowhere else ({@code LaunchOwnerGateSpec}): {@link
 * GroupedLaunch} registers the child's pid once {@code start()} has returned — a launch that threw
 * started no group and registers nothing — and {@link ProcessGroupSweep} deregisters it when its
 * zero-signal probe is refused. A sweep that ends after its last round with the probe still answered,
 * or with no answer at all, leaves the group registered, so the shutdown step tries it again. Neither
 * writer is reachable from outside the module: the class and its methods are package-private, and the
 * one reader outside it is {@link ProcessGroupSweep#sweepLive()}.
 *
 * <p>Process-wide, deliberately: the Local Default constructors of the module build the launch owner
 * and the sweep at many sites — every {@code CaptureRunner()}, {@code GitExec}, {@code DockerCli} —
 * and the JVM's shutdown hook has to reach all of them, with no object any of them could be handed
 * through. {@link #PROCESS} is that one registry; it is fully built by its constructor and published
 * by the class's own initialisation, and its set is a concurrent one, so a launch on one thread and a
 * sweep on another need no lock and the shutdown step iterates a copy, holding nothing across the
 * {@code kill}s it sends ({@code lock-scope.md}). A spec builds a registry of its own, so no feature
 * leaks a group into the next.
 *
 * <p>Named limits: a JVM ended by {@code SIGKILL} runs no hook and sweeps nothing; and between a
 * leader's reap and its deregistration its pid may be reused by an unrelated group leader, a window
 * kept short by deregistering at the first refused probe.
 *
 * <p>Implements FR21 of fix-terminal-receipt-convergence (D19, fourth amendment).
 */
final class LiveGroups {

    /** The process's one registry, written by every grouped launch and every sweep of this JVM. */
    static final LiveGroups PROCESS = new LiveGroups();

    private final Set<Long> groups = ConcurrentHashMap.newKeySet();

    /** Records that the group {@code group} leads was started; called once its {@code start()} returned. */
    void register(long group) {
        groups.add(group);
    }

    /** Forgets the group {@code group}: its zero-signal probe was refused, so it has no member left. */
    void deregister(long group) {
        groups.remove(group);
    }

    /** Returns a copy of the groups that may still have members, so no reader iterates the live set. */
    List<Long> current() {
        return List.copyOf(groups);
    }
}
