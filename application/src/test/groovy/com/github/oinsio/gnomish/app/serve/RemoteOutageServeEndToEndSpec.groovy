package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * M4, G5 of add-base-ref-resolution (task 7.5): the end-to-end proof that the feed
 * ({@link FeedAutomaton}/{@link FeedCycle}), the remote outage gate ({@link RemoteOutageGate}) and a
 * slot's own outage signal ({@link TakeSlotRunner#signalRemoteOutageGate}, mirrored here by a
 * lightweight {@link SlotRunner} double) compose correctly on virtual time: three slots claim three
 * seeded tasks, the remote goes dead for an hour, and comes back.
 *
 * <p>The scenario drives a REAL {@link InMemoryTracker} (so the tracker-state read-back — Ready
 * again, zero abort facts — is genuine) and a REAL {@link RemoteOutageGate} wired to a stubbed
 * {@link BaseRefGit} whose {@code probe} answers strictly off the SAME {@link VirtualClock} the
 * automaton runs on: false before the one-hour recovery instant, true at and after it. The double
 * standing in for {@code TakeSlotRunner} does exactly what that class's own javadoc documents: an
 * outage-shaped outcome opens the gate and releases the claim back to {@code Ready}; a
 * post-recovery outcome confirms the refresh instead.
 *
 * <p><b>Scope of its "one WARN" assertions.</b> The capture below is attached to {@link
 * RemoteOutageGate}'s own logger, so it proves the gate transitions exactly once per outage — NOT
 * that the console as a whole sees one WARN, since the slot double here stands in for the real
 * emitters around it. {@code OutageWarnFanOutSpec} owns that second invariant: it walks one
 * released task through {@code FreshClaimBaseBinding} and {@link SlotOutcomeLog} for real and
 * counts every WARN a console would show.
 *
 * <p>G5 ("a dead remote costs the tracker nothing beyond the claims already in flight when it
 * died") and M4 ("at most one claim per slot for the whole outage ... claimable again after the
 * first successful probe") are both read directly off {@link FeedCycle#claimOrAbandon}: once
 * {@link RemoteOutageGate#isOpen} is true, the claim walk is never entered at all — no {@code
 * tracker.claim} call happens while the gate is open, by construction, not by a timing accident
 * this spec has to race. The three claims that trigger the outage are the ONLY claims made while
 * the gate is open or opening; the fourth claim recorded below happens only after the recovering
 * probe has already closed the gate, in the same {@link FeedCycle#poll} call that closed it — proof
 * that the first successful probe strictly precedes the first post-outage claim (FR14, NFR-R3).
 */
class RemoteOutageServeEndToEndSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration BACKOFF_BASE = Duration.ofMinutes(2)
    private static final Duration BACKOFF_CAP = Duration.ofHours(1)
    private static final Duration PROBE_CAP = Duration.ofMinutes(10)
    private static final Duration OUTAGE_DURATION = Duration.ofHours(1)
    private static final Duration SUSTAINED_OPEN_THRESHOLD = Duration.ofHours(3)
    private static final int WIP_LIMIT = 3

    // Index/fraction always zero: candidate order and probe-interval jitter are exact, not
    // randomized, so this scenario's step count is deterministic.
    private static final class FixedRandom extends Random {
        @Override
        int nextInt(int bound) {
            0
        }

        @Override
        double nextDouble() {
            0.0d
        }
    }

    private final VirtualClock clock = new VirtualClock()
    // Budgeted: a mutant that removes the gate's "no claim while open" check would send this
    // automaton back onto FeedOutageRetry's OWN retry-forever path (it never does today, since the
    // gate check runs before any tracker call is attempted) — the budget turns a would-be hang into
    // a red assertion rather than a stuck spec (see BudgetedVirtualSleeper's Javadoc).
    private final BudgetedVirtualSleeper sleeper = new BudgetedVirtualSleeper(clock)
    private final Instant start = clock.now()
    private final Instant recoversAt = start + OUTAGE_DURATION

    private static final List<TaskRef> REFS = [
        new TaskRef('github:o/r#1'),
        new TaskRef('github:o/r#2'),
        new TaskRef('github:o/r#3'),
    ]

    private InMemoryTracker tracker = new InMemoryTracker()
    private InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        REFS.eachWithIndex { ref, i ->
            harness.seed(
            ref, new TaskSnapshot(ref.id(), "task ${i + 1}" as String, 'body'),
            new TrackerTaskState.Ready(), AbortFacts.none())
        }
    }

    private RemoteOutageGate gate(BaseRefGit baseRefGit) {
        def suppressor = new RepeatSuppressor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(5))
        new RemoteOutageGate(
                baseRefGit, Path.of('.'), clock, new FixedRandom(), IDLE, PROBE_CAP,
                new RemoteOutageWiring('origin', suppressor, SUSTAINED_OPEN_THRESHOLD, {}, { ignored -> }))
    }

    // Waits for the async slot virtual-thread(s) started by FeedCycle.startSlot to actually run —
    // step()/drain() return before that thread necessarily executes (same race FeedAutomatonSpec's
    // own awaitSize() guards against).
    private static void awaitSize(List<?> sink, int expectedSize) {
        new PollingConditions(timeout: 2).eventually {
            assert sink.size() == expectedSize
        }
    }

    def "a remote dead for an hour then back: one WARN, one recovery line, zero abort facts, tasks claimable again (M4, G5)"() {
        given: 'a probe that fails until the recovery instant, then succeeds, off the SAME virtual clock the feed runs on'
        BaseRefGit baseRefGit = [probe: { Path p ->
                !clock.now().isBefore(recoversAt)
            }] as BaseRefGit
        def gate = gate(baseRefGit)
        def logs = LogCaptureSupport.attach(RemoteOutageGate)

        and: 'a SlotRunner double mirroring TakeSlotRunner.signalRemoteOutageGate: while the remote is dead, a claimed slot opens the gate and releases its claim; once recovered, it confirms the refresh instead'
        def claims = new CopyOnWriteArrayList<TaskRef>()
        def releases = new CopyOnWriteArrayList<TaskRef>()
        // The three initial claims land at virtual-thread speed with no simulated round work in
        // between, so without this barrier the FIRST slot's own openOnFailure() would open the gate
        // before the second and third automaton.step() calls ever got to attempt their claim — a
        // real race, not the scenario this spec means to prove. The barrier holds all three until
        // every slot has actually claimed, mirroring three real rounds that were already in flight
        // when a shared remote died under all of them at once.
        def allClaimed = new CountDownLatch(3)
        SlotRunner runner = { TaskRef ref ->
            claims.add(ref)
            allClaimed.countDown()
            allClaimed.await()
            if (clock.now().isBefore(recoversAt)) {
                gate.openOnFailure('origin unreachable: connection refused')
                // The real production release (FreshClaimBaseBinding.release, mirrored here) is a
                // documented no-op on logical state for BOTH shipped adapters (InMemoryTracker only
                // clears the claim marker; GithubTracker's release does nothing at all) — a claim
                // left this way stays Working, "in flight" exactly as G5 describes, until the
                // heartbeat/reaper lease-maintenance machinery (add-claim-heartbeat, a different
                // change) notices the stale claim and independently sweeps it back to Ready. This
                // spec's own scope is the feed/gate composition, not that sweep, so
                // returnToReady(ref) stands in for "the reaper already did its job by the time the
                // remote came back" — the one simplifying assumption this scenario makes, so the
                // claimable-again assertion below observes a real Ready-state task rather than
                // reaching into heartbeat/TTL machinery this change never touches.
                tracker.release(ref)
                harness.returnToReady(ref)
                releases.add(ref)
            } else {
                gate.onSuccessfulRefresh()
            }
        } as SlotRunner

        and: 'three slots, three ready tasks, a WIP limit that never blocks this scenario'
        def ledger = new SlotLedger(3)
        def automaton = new FeedAutomaton(
                tracker, INSTANCE, ledger, runner, sleeper, clock,
                BACKOFF_BASE, BACKOFF_CAP, IDLE, WIP_LIMIT, new FixedRandom(),
                DirtyNotifier.NOOP, gate)

        when: 'the feed fills all three slots — the only claims the outage costs the tracker (G5)'
        3.times { automaton.step() }
        awaitSize(claims, 3)
        awaitSize(releases, 3)

        then: 'each of the three slots hit the simulated outage and opened the gate exactly once (idempotent while already open)'
        gate.health().open()
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        logs.list.findAll {
            it.level == Level.WARN
        }[0].formattedMessage.startsWith(OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.head())

        and: 'every seeded task was released back to Ready, with no abort marker recorded for any of them'
        REFS.every { ref ->
            tracker.fetchTask(ref).state() instanceof TrackerTaskState.Ready
        }
        REFS.every { ref -> tracker.fetchTask(ref).abortFacts().count() == 0 }

        when: 'time passes while the remote stays dead — the feed keeps polling, but the open gate abandons every permit before ever calling tracker.claim'
        5.times {
            clock.advance(PROBE_CAP)
            automaton.step()
        }

        then: 'no further claim happened anywhere in the outage window: still the same three from the initial fill'
        claims.size() == 3
        gate.health().open()
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        logs.list.findAll { it.level == Level.ERROR }.isEmpty()

        when: 'the clock reaches the one-hour mark: the SAME poll() call that runs the now-successful probe (closing the gate) also re-polls and claims — proving the probe strictly precedes the claim'
        clock.advance(PROBE_CAP)
        automaton.step()
        awaitSize(claims, 4)

        then: 'the gate closed with exactly one INFO recovery line, never a second WARN'
        !gate.health().open()
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        def recoveries = logs.list.findAll { it.level == Level.INFO }
        recoveries.size() == 1
        recoveries[0].formattedMessage.contains('5 failed probe')

        and: 'exactly one task was claimable again and got reclaimed the moment the gate reopened for business'
        claims.size() == 4
        REFS.count { ref ->
            tracker.fetchTask(ref).state() instanceof TrackerTaskState.Working
        } == 1
        REFS.count { ref ->
            tracker.fetchTask(ref).state() instanceof TrackerTaskState.Ready
        } == 2

        and: 'zero abort facts and zero tracker escalations for every task, throughout the whole scenario'
        REFS.every { ref -> tracker.fetchTask(ref).abortFacts().count() == 0 }
        REFS.every { ref ->
            !(tracker.fetchTask(ref).state() instanceof TrackerTaskState.AwaitingHuman)
        }

        cleanup:
        logs.detach()
    }
}
