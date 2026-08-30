package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickListener
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Clock
import java.time.Duration
import spock.lang.Specification
/**
 * FR2, FR13, FR14 (design D9, D10) of add-factory-serve: {@link ServeAssembly}'s three remaining
 * leaf builders. {@code ServeAssemblySpec} covers {@code shutdown}; this covers the rest, to the
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
    2, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14, null)

    // FR13: the slot runner is built over the caller's tracker, so a slot it runs consults THAT
    // tracker. Driven here through the fetch that opens every slot — the runner swallows its
    // failure by design, which makes the call itself the observable.
    def "builds a slot runner wired over the caller's own tracker"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit))
        def heartbeat = TakeHeartbeat.forRun(tracker, new TrackerConfig('github', 3), { Duration d -> } as Sleeper)

        when:
        def slotRunner = ServeAssembly.slotRunner(
                new ServeArguments(CLONE_DIR, null, false), WORKTREES_ROOT, 'taskId', pipeline(),
                new TrackerConfig('github', 3), Stub(TrackerAdapterFactory), tracker, INSTANCE,
                assemblyRunning(null), git, heartbeat, FIXED_CLOCK, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())

        then:
        slotRunner != null

        when: 'the runner opens a slot for a claimed ref'
        slotRunner.run(REF)

        then: "it consulted the caller's tracker, and its own failure did not escape the slot"
        1 * tracker.fetchTask(REF) >> {
            throw new IllegalStateException('tracker unreachable')
        }
        noExceptionThrown()
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
        def automaton = ServeAssembly.feedAutomaton(testProperties(), SERVE_PROPERTIES, clock, trackerConfig,
                Stub(Tracker), INSTANCE, new SlotLedger(2, clock, notifier), null, notifier)

        then:
        automaton.view().wipLimit() == 7
    }

    // FR14, D10: the janitor disposes through the task-git port's OWN bound disposer, resolved for
    // the caller's clone and worktrees root — it must never build a git subprocess of its own
    // (task 4.4 of split-into-modules removed exactly that).
    def "builds a worktree janitor disposing through the caller's git port"() {
        given:
        def worktrees = Mock(TaskWorktreeGit)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), worktrees)

        when:
        def janitor = ServeAssembly.worktreeJanitor(new ServeArguments(CLONE_DIR, null, false),
                WORKTREES_ROOT, SERVE_PROPERTIES, new SlotLedger(1), git)

        then:
        1 * worktrees.environmentDisposal(CLONE_DIR, WORKTREES_ROOT) >> Stub(TaskEnvironmentDisposal)
        janitor != null
    }

    // NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle: a host-only install has no sandbox to sweep,
    //     so its tick stays unobserved — no all-zero vital, no ledger line every cadence.
    def "a host-only install's tick is not observed"() {
        given:
        def tickLog = new SweepTickLog(
                Duration.ofDays(7), Clock.systemUTC(), 20)
        def ticks = []
        def livenessOracle = new LivenessOracle(
                new CachedOpenTaskListing(),
                new StalenessMemory(
                        new SystemMonotonicTime(), Duration.ofMinutes(1)))

        when:
        def tick = ServeAssembly.sandboxLifecycleTick(
                new ServeArguments(CLONE_DIR, null, false),
                SERVE_PROPERTIES,
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
                Duration.ofDays(7), Clock.systemUTC(), 20)

        when:
        def tick = ServeAssembly.sandboxLifecycleTick(
                new ServeArguments(CLONE_DIR, null, false),
                SERVE_PROPERTIES,
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
