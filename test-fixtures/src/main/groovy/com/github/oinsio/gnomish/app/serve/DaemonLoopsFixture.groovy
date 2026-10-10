package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.LiveClaims
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Duration

/**
 * {@link DaemonLoops} for a spec whose subject is the shutdown sequence around them, not the loops
 * themselves: the standing reaper, the worktree janitor and the sandbox sweep tick, real objects
 * over no-op duties, never started. Stopping a never-started loop is a no-op (design D4 of
 * supervise-daemon-loops-and-embed-dashboard), so {@link ServeShutdown} runs its whole sequence
 * over them with no thread involved. Shared by the {@code ServeShutdown} specs in {@code
 * :application} and {@code ServeShutdownWiringSpec} in {@code :bootstrap}; the loops' own stop
 * ordering is pinned by {@code ServeShutdownDaemonLoopsSpec}.
 *
 * <p>Implements D9 of supervise-daemon-loops-and-embed-dashboard (test support).
 */
final class DaemonLoopsFixture {

    private DaemonLoopsFixture() {}

    /**
     * @param root a folder of the spec's own; the janitor's clone is laid out under it but never
     *     scanned, since the loops are never started
     */
    static DaemonLoops inert(Path root) {
        def time = VirtualTimeEquipment.on(new VirtualClock(), { Duration d -> } as Sleeper)
        def reaper = new StandingReaper(ReaperDuty.NONE, Duration.ofSeconds(30), {
            []
        } as LiveClaims, time)
        def clone = RegisteredCloneFixture.unregistered(root.resolve('home'), root.resolve('clone'))
        def janitor = new WorktreeJanitor(clone, Duration.ofDays(14), { String key -> } as TaskEnvironmentDisposal,
        time, { -> Set.of() } as OccupiedSlots)
        def oracle = new LivenessOracle(
                new CachedOpenTaskListing(), new StalenessMemory(new SystemMonotonicTime(), Duration.ofMinutes(1)))
        def sweep = new SandboxLifecycleTick(SandboxLifecyclePass.NONE, oracle, root, Duration.ofMinutes(5), time)
        new DaemonLoops(reaper, janitor, sweep)
    }
}
