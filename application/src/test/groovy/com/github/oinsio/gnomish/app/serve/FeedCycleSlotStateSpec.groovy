package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.FinishedDecline
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.logging.RepeatSuppressorFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * The scheduler-slot state is the instance's own (FR2 of add-tracker-port; FR9 of
 * add-factory-serve): while the feed fills its slots and they free again, the tracker is read and
 * claimed through, and nothing else — no write records how many slots are taken or free.
 */
class FeedCycleSlotStateSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')

    Tracker tracker = Mock()
    SlotLedger ledger = new SlotLedger(2, new VirtualClock())
    CountDownLatch finishTasks = new CountDownLatch(1)

    private FeedCycle cycle() {
        def outageRetry = new FeedOutageRetry(new BudgetedVirtualSleeper(new VirtualClock()), {
            Duration.ofSeconds(1)
        }, RepeatSuppressorFixture.quiet())
        def resilience = new FeedResilience(
                outageRetry, new FinishedDecline(RepeatSuppressorFixture.quiet()), RemoteOutageGateFixtures.closedGate())
        // Each slot runs until the spec lets its task finish, so the slots really are full at once.
        def runner = { TaskRef ref ->
            finishTasks.await(5, TimeUnit.SECONDS)
        } as SlotRunner
        new FeedCycle(new FeedTracker(tracker, INSTANCE), ledger, runner,
                new FeedSelection(Duration.ofMinutes(2), Duration.ofHours(1), 2, new Random(0)),
                new FeedStateLogger(), resilience)
    }

    private void fillOneSlot(FeedCycle cycle) {
        ledger.acquire()
        def poll = cycle.poll(new VirtualClock().instant())
        cycle.claimOrAbandon(poll.candidates().findAll {
            !(it.ref() in ledger.occupiedRefs())
        })
    }

    // FR2 of add-tracker-port: the tracker sees task-state transitions and their markers only —
    //     the slots filling to full and freeing again write nothing to it
    // Scenario: tracker/tracker-port / The scheduler-slot state never reaches the tracker
    def "slots filling and freeing reach the tracker as reads and claims only"() {
        given:
        def feed = cycle()

        when: 'both slots fill, the instance runs full, and both tasks finish'
        fillOneSlot(feed)
        fillOneSlot(feed)
        def freeWhileFull = ledger.freeSlots()
        finishTasks.countDown()
        def drained = ledger.awaitDrained(Duration.ofSeconds(5))

        then: 'the slots really filled and freed'
        freeWhileFull == 0
        drained
        ledger.freeSlots() == 2

        and: 'the tracker was only listed and claimed through'
        _ * tracker.listReady(_) >> [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ]
        _ * tracker.listOpen() >> []
        2 * tracker.claim(_, INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        0 * tracker._
    }

    private static ReadyTask ready(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true, false, UntrustedText.tracker('fixture title'))
    }
}
