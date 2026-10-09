package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.LiveClaims
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Timeout

/**
 * {@link ServeShutdown} stops the {@link DaemonLoops} — the standing reaper, the worktree janitor
 * and the sandbox sweep tick — before its grace wait (design D9 of
 * supervise-daemon-loops-and-embed-dashboard), observed on real loop threads: each loop counts its
 * own ticks, a slot stays occupied so the shutdown sits in its grace wait, and no loop ticks again
 * while it does. {@link DaemonLoops#start} is what starts the three, as {@code ServeCommand} does.
 *
 * <p>Implements D9 of supervise-daemon-loops-and-embed-dashboard; FR4 of fix-reaper-idle-liveness.
 */
@Timeout(30)
class ServeShutdownDaemonLoopsSpec extends ServeShutdownSpecBase {

    private static final Duration TICK = Duration.ofMillis(5)
    private static final Duration LONG_GRACE = Duration.ofSeconds(20)

    def reaperTicks = new AtomicInteger()
    def janitorTicks = new AtomicInteger()
    def sweepTicks = new AtomicInteger()
    DaemonLoops loops

    // Sleeps for real, briefly, and honours the stop's interrupt as the production sleeper does:
    // the flag is restored and nothing is thrown, so a stopped loop ends quietly (design D3/D4).
    private static final Sleeper BRIEF = { Duration d ->
        try {
            Thread.sleep(2)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    } as Sleeper

    def setup() {
        def time = VirtualTimeEquipment.on(new VirtualClock(), BRIEF)
        def reaper = new StandingReaper({ refs ->
            reaperTicks.incrementAndGet()
        } as ReaperDuty, TICK, {
            []
        } as LiveClaims, time)
        // The janitor reads its held slots on every tick that finds its worktree folder.
        def clone = RegisteredCloneFixture.unregistered(loopsRoot.resolve('home'), loopsRoot.resolve('clone'))
        Files.createDirectories(clone.worktrees())
        def janitor = new WorktreeJanitor(clone, Duration.ofDays(14), { String key -> } as TaskEnvironmentDisposal, time, {
            ->
            janitorTicks.incrementAndGet()
            Set.of()
        } as OccupiedSlots)
        def oracle = new LivenessOracle(
                new CachedOpenTaskListing(), new StalenessMemory(new SystemMonotonicTime(), Duration.ofMinutes(1)))
        def pass = { dir, liveness ->
            sweepTicks.incrementAndGet(); ''
        } as SandboxLifecyclePass
        def sweep = new SandboxLifecycleTick(pass, oracle, loopsRoot, TICK, time)
        loops = new DaemonLoops(reaper, janitor, sweep)
    }

    def cleanup() {
        loops.stop()
    }

    private List<Integer> ticks() {
        [
            reaperTicks.get(),
            janitorTicks.get(),
            sweepTicks.get()
        ]
    }

    private static void awaitTrue(Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assert System.nanoTime() <deadline: 'condition not reached within 10 s'
            Thread.sleep(2)
        }
    }

    // factory-serve "No cleaner run during drain": SIGTERM arrives while a slot is still releasing
    //     within the grace window; no worktree cleaner or sandbox sweep run starts after the stop.
    def "D9: the reaper, the janitor and the sweep tick all stop before the grace wait"() {
        given: 'a slot still occupied, so the shutdown waits out its grace window'
        def ledger = new SlotLedger(1, new VirtualClock())
        ledger.acquire()
        ledger.assign(A)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, LONG_GRACE, killer, loops)

        and: 'all three loops started and ticking'
        loops.start()
        awaitTrue { ticks().every { it> 1 } }

        when: 'the signal lands and the sequence enters its grace wait'
        def signal = Thread.ofVirtual().start { shutdown.shutdown(null) }
        awaitTrue { flag.isLost(A) }
        Thread.sleep(50) // a tick already in progress at the stop completes (design D4)
        def atStop = ticks()
        Thread.sleep(150) // dozens of intervals for any loop that was still running

        then: 'the shutdown is still inside the grace wait — nothing killed, the slot still releasing'
        signal.isAlive()
        killer.calls.get() == 0

        and: 'no loop ticked during it'
        ticks() == atStop

        when: 'the slot releases'
        ledger.release(A)
        signal.join(10_000)

        then: 'the sequence finishes'
        !signal.isAlive()
        killer.calls.get() == 1
    }

    // factory-serve "second pass is a no-op": the hook and the drain path may both run the sequence.
    def "D9: a second shutdown is a no-op for the stopped loops"() {
        given:
        def ledger = new SlotLedger(1, new VirtualClock())
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, new ClaimLossFlag(), Duration.ofMillis(50), killer, loops)
        loops.start()
        awaitTrue { ticks().every { it> 0 } }
        shutdown.shutdown(null)
        Thread.sleep(50)
        def afterFirst = ticks()

        when:
        shutdown.shutdown(null)
        Thread.sleep(150)

        then: 'nothing thrown, no loop resumed, and the sequence ran to its end again'
        noExceptionThrown()
        ticks() == afterFirst
        killer.calls.get() == 2
    }
}
