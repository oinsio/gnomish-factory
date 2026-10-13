package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.lease.Reaper
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.VirtualMonotonicTime
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * FR6, FR13 of add-factory-serve; FR1 of fix-reaper-idle-liveness: the WIP limit and the standing
 * reaper meet on one real {@link InMemoryTracker}. The feed is held Idle-blocked by a front whose
 * holder died; the reaper's own sweep — not a human — returns it, the open-front count drops below
 * W, and the feed's next step claims work again — the returned task first, ahead of fresh work.
 * The feed runs one {@code step()} at a time on virtual time; the reaper is the real {@link Reaper}
 * over a real {@link StalenessMemory}.
 */
class FeedUnblockedByReapingSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final TaskRef STALE = new TaskRef('PROJ-STALE')
    private static final TaskRef FRESH = new TaskRef('PROJ-FRESH')
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final int WIP_LIMIT = 1

    private final InMemoryTracker tracker = new InMemoryTracker()
    private final VirtualClock clock = new VirtualClock()
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final FeedAutomaton feed = FeedAutomatonFixture.feedAutomaton(
    tracker, INSTANCE, new SlotLedger(2, clock), { TaskRef ignored ->
        null
    } as SlotRunner,
    VirtualTimeEquipment.on(clock, new BudgetedVirtualSleeper(clock)),
    Duration.ofMinutes(2), Duration.ofHours(1), Duration.ofSeconds(30), WIP_LIMIT, new FixedRandom())
    private final Reaper reaper = new Reaper(tracker, new StalenessMemory(monotonic, TTL))

    def setup() {
        def harness = new InMemoryTrackerHarness(tracker)
        harness.seedWorkingWithClaim(tracker, STALE, 'gnomish-dead-0a1b2c')
        harness.seed(FRESH, new TaskSnapshot(FRESH.id(), UntrustedText.tracker('fresh'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    // F96: scenario text aligned to what this test asserts
    // Scenario: serve/factory-serve / Reaping unblocks a WIP-blocked feed
    def "FR13: the reaper returns a stale front, the open-front count drops below W, and the feed claims the returned task first"() {
        when: 'the feed polls with one Working front at W = 1'
        def blocked = feed.step()

        then: 'it is Idle-blocked and claims nothing'
        blocked == FeedState.IDLE_BLOCKED
        tracker.fetchTask(FRESH).state() instanceof TrackerTaskState.Ready

        when: 'the front stays unbeaten for a whole TTL across two reaper sweeps'
        reaper.reapOnce([])
        monotonic.advance(TTL)
        reaper.reapOnce([])

        then: 'the reaper returned it to Ready, and the open fronts are below W'
        tracker.fetchTask(STALE).state() instanceof TrackerTaskState.Ready
        tracker.listOpen().size() <WIP_LIMIT

        when: 'the feed steps again, with no human involved'
        feed.step()

        then: 'it claims work again: the returned task, ahead of the fresh one'
        // The claim lands inside step(), before the slot's thread starts, so the tracker answers
        // at once; the slot's own launch is FeedCycleSpec's subject.
        tracker.fetchTask(STALE).state() == new TrackerTaskState.Working(INSTANCE.value())
        tracker.fetchTask(FRESH).state() instanceof TrackerTaskState.Ready
    }
}
