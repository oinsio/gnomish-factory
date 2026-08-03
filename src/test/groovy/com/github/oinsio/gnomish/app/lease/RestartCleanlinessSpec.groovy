package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Duration
import spock.lang.Specification

/**
 * FR12 of add-factory-serve, "Restart is a clean start": proves the "Claims of the previous
 * life" scenario against the REAL {@link InMemoryTracker} — the reference adapter, where claim
 * state genuinely lives (design D15) — rather than restating the trivial fact that {@link
 * InstanceId#generate} mints a fresh id each call.
 *
 * <p>A "previous life" claims two tasks under an old instance id, seeded directly via {@link
 * InMemoryTrackerHarness#seedWorkingWithClaim} (bypassing {@code claim}, exactly like a claim a
 * now-dead process actually made). The "restart" then constructs a BRAND NEW {@link
 * StalenessMemory}, {@link Reaper}, and {@link StandingReaper} — nothing carried over from the
 * previous life's objects, mirroring two separate {@code ServeCommand.run} invocations that share
 * no static or instance-local state (there is none to share: {@link ServeCommand} mints {@link
 * InstanceId#generate} fresh and no claim-ownership file/cache exists anywhere in the app layer).
 *
 * <p>The reaper is a standing duty independent of any instance's own claims (design D1 of
 * fix-reaper-idle-liveness, superseding the old beat-riding reaper of design D4): the new
 * instance's live-claims snapshot supplier always returns empty, modelling "the new instance
 * holding nothing", since it never claimed anything of its own before the restarted process's
 * first {@code StandingReaper} tick. The old claims are only ever visible to it as foreign
 * entries through {@code listOpen}, indistinguishable from any other instance's stale claim
 * ({@link ReapingWhileSaturatedSpec}). Once the TTL elapses on the new instance's own monotonic
 * clock, the reaper removes them and the ordinary queue re-claims them for the new instance —
 * proving "left to the lease protocol... may well be re-claimed by the new process through the
 * ordinary queue" end to end, with no instance-local state anywhere.
 *
 * <p>Implements FR12 of add-factory-serve; FR1, FR2 of fix-reaper-idle-liveness.
 */
class RestartCleanlinessSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef TASK_A = new TaskRef('github:o/r#life1-a')
    private static final TaskRef TASK_B = new TaskRef('github:o/r#life1-b')

    private final InMemoryTracker tracker = new InMemoryTracker()
    private final InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def "claims of the previous life are not adopted, go stale, and return via the reaper"() {
        given: 'a previous instance life held two claims, now dead — no process survives it'
        String oldInstanceId = InstanceId.generate('gnomish-factory').value()
        harness.seedWorkingWithClaim(tracker, TASK_A, oldInstanceId)
        harness.seedWorkingWithClaim(tracker, TASK_B, oldInstanceId)
        def versionBefore = { TaskRef ref -> tracker.listOpen().find { it.ref() == ref }.claimVersion() }
        def originalVersionA = versionBefore(TASK_A)
        def originalVersionB = versionBefore(TASK_B)

        and: 'a restarted instance: a fresh id and brand-new lease collaborators, nothing reused'
        String newInstanceId = InstanceId.generate('gnomish-factory').value()
        def monotonic = new VirtualMonotonicTime()
        def staleness = new StalenessMemory(monotonic, TTL)
        def reaper = new Reaper(tracker, staleness)
        // The new instance holds nothing of its own, so the standing reaper's live-claims
        // snapshot supplier always returns empty — exactly the shape of a just-restarted process
        // that has not claimed anything yet (FR1, FR2).
        def standingReaper = new StandingReaper(reaper, { Duration d -> }, INTERVAL, { -> [] })

        expect: 'the restart alone mints a different id — the two lives are never confused'
        newInstanceId != oldInstanceId

        when: 'the new instance ticks right after starting, having claimed nothing of its own'
        standingReaper.tick()

        then: 'the old claims are not adopted: still held by the old instance, untouched by a beat'
        tracker.fetchTask(TASK_A).state() == new TrackerTaskState.Working(oldInstanceId)
        tracker.fetchTask(TASK_B).state() == new TrackerTaskState.Working(oldInstanceId)
        versionBefore(TASK_A) == originalVersionA
        versionBefore(TASK_B) == originalVersionB

        when: 'the claims go stale on the new instance\'s own monotonic clock, past the TTL'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'the reaper returns both tasks to Ready — the ordinary lease protocol, not adoption'
        tracker.fetchTask(TASK_A).state() == new TrackerTaskState.Ready()
        tracker.fetchTask(TASK_B).state() == new TrackerTaskState.Ready()

        and: 'the new process may now re-claim them through the ordinary queue'
        tracker.claim(TASK_A, newInstanceId) instanceof ClaimResult.Acquired
        tracker.claim(TASK_B, newInstanceId) instanceof ClaimResult.Acquired
    }
}
