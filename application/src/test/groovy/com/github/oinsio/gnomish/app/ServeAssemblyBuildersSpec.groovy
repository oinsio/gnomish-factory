package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickListener
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
/**
 * FR2, FR14 (design D9, D10) of add-factory-serve: {@link ServeAssembly}'s remaining leaf
 * builders (the slot run order moved to {@code ServeArguments}, {@code ServeArgumentsSpec}). {@code ServeAssemblySpec} covers {@code shutdown}; this covers the rest, to the
 * same standard — each scenario proves the returned collaborator is wired over the caller's OWN
 * objects, not merely that something non-null came back.
 *
 * <p>A separate file rather than scenarios added to {@code ServeAssemblySpec}: pre-existing specs
 * are not edited by this change (M5 of split-into-modules).
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class ServeAssemblyBuildersSpec extends Specification implements RunChainFakes {

    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    2, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14, null, null, null, null)

    // FR18 of supervise-daemon-loops-and-embed-dashboard (task 3.9): the daemon's heartbeat is built
    // on this assembly's one time equipment — the runtime assembly holds no time of its own to
    // hand it — so its first stamp is the assembly clock's instant, over the caller's served tracker.
    def "builds the daemon heartbeat on the assembly's own clock"() {
        given:
        def start = Instant.parse('2026-05-06T07:08:09Z')
        def served = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, new TrackerConfig('github', 3),
                Stub(TrackerAdapterFactory), Stub(Tracker), INSTANCE)

        when:
        def heartbeat = new ServeAssembly(testProperties(), SERVE_PROPERTIES, VirtualTimeEquipment.on(new VirtualClock(start)), null)
                .heartbeat(served, new ForwardingDirtyNotifier())

        then:
        (heartbeat.instance() as InstanceHeartbeat).lastTickAt() == start
    }

    // NFR-O1 of add-serve-sandbox-lifecycle; task 3.9: the sweep tick log stamps each completed tick
    // from the assembly's clock, so the dashboard's vital and the daemon read one time source.
    def "builds the sweep tick log on the assembly's own clock"() {
        given:
        def start = Instant.parse('2026-05-06T07:08:09Z')
        def log = new ServeAssembly(null, SERVE_PROPERTIES, VirtualTimeEquipment.on(new VirtualClock(start)), null)
                .sweepTickLog(Duration.ofHours(1))

        when:
        log.beginTick()
        def record = log.endTick()

        then:
        record.tickAt() == start
    }

    // FR2: the feed automaton enforces the WIP limit from the caller's OWN tracker config — the
    // limit is what decides whether a fresh task may start, so a builder that dropped it would
    // silently uncap the daemon.
    def "builds a feed automaton carrying the caller's configured WIP limit"() {
        given:
        def clock = new VirtualClock()
        def notifier = new ForwardingDirtyNotifier()
        def trackerConfig = new TrackerConfig('github', 3, Duration.ofMinutes(5), 3, 7, [:] as Map)

        when:
        def automaton = new ServeAssembly(testProperties(), SERVE_PROPERTIES, VirtualTimeEquipment.on(clock), null).feedAutomaton(trackerConfig,
                Stub(Tracker), INSTANCE, new SlotLedger(2, clock, notifier), null, notifier,
                RemoteOutageGates.forServe(BaseRefGit.UNWIRED, CLONE_DIR, new ServeProperties(0, null, null, null, null, null, null, null, null, null), new VirtualClock(), {}, { ignored -> }))

        then:
        automaton.view().wipLimit() == 7
    }

    // FR14, D10: the janitor disposes through the task-git port's OWN bound disposer, resolved for
    // the registered clone (FR9 of add-project-registry) — it must never build a git subprocess of
    // its own (task 4.4 of split-into-modules removed exactly that).
    def "builds a worktree janitor disposing through the caller's git port for the registered clone"() {
        given:
        def worktrees = Mock(TaskWorktreeGit)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), worktrees, new ClaimEpochBook())

        when:
        def janitor = new ServeAssembly(null, SERVE_PROPERTIES, VirtualTimeEquipment.create(), RegisteredCloneFixture.provider(CLONE))
                .worktreeJanitor(new SlotLedger(1, new VirtualClock()), git)

        then:
        1 * worktrees.environmentDisposal(CLONE) >> Stub(TaskEnvironmentDisposal)
        janitor != null
    }

    // NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle: a host-only install has no sandbox to sweep,
    //     so its tick stays unobserved — no all-zero vital, no ledger line every cadence.
    def "a host-only install's tick is not observed"() {
        given:
        def tickLog = new SweepTickLog(
                Duration.ofDays(7), new VirtualClock(), 20)
        def ticks = []
        def livenessOracle = new LivenessOracle(
                new CachedOpenTaskListing(),
                new StalenessMemory(
                        new SystemMonotonicTime(), Duration.ofMinutes(1)))

        when:
        def tick = new ServeAssembly(null, SERVE_PROPERTIES, VirtualTimeEquipment.create(), null).sandboxLifecycleTick(
                new ServeArguments(CLONE_DIR, null, false, false, null),
                SandboxLifecyclePass.NONE,
                livenessOracle,
                tickLog,
                SweepVerdictListener.IGNORE, { r ->
                    ticks << r
                } as SweepTickListener)
        tick.tick()

        then:
        tickLog.lastTick() == null
        ticks.isEmpty()
    }

    // FR6, design D7 of add-serve-sandbox-lifecycle: the sweep-lifecycle tick is built over the
    // caller's own pass and liveness oracle, at the configured cadence.
    def "builds a sandbox lifecycle tick wired over the caller's own pass and liveness oracle"() {
        given:
        def calls = []
        SandboxLifecyclePass pass = { dir, liveness ->
            calls << dir
            ''
        }
        def livenessOracle = new LivenessOracle(
                new CachedOpenTaskListing(),
                new StalenessMemory(
                        new SystemMonotonicTime(), Duration.ofMinutes(1)))

        def realTickLog = new SweepTickLog(
                Duration.ofDays(7), new VirtualClock(), 20)

        when:
        def tick = new ServeAssembly(null, SERVE_PROPERTIES, VirtualTimeEquipment.create(), null).sandboxLifecycleTick(
                new ServeArguments(CLONE_DIR, null, false, false, null),
                pass,
                livenessOracle,
                realTickLog,
                SweepVerdictListener.IGNORE,
                SweepTickListener.IGNORE)
        tick.tick()

        then:
        tick != null
        calls == [CLONE_DIR]

        and: 'NFR-O1 of add-serve-sandbox-lifecycle: a real pass IS observed, so the tick is recorded'
        realTickLog.lastTick() != null
    }
}
