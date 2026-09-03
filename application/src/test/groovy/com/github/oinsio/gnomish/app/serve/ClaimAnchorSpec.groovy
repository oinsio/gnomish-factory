package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
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
import com.github.oinsio.gnomish.logtext.MdcAwareThread
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.logging.RepeatSuppressorFixture
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.MDC
import spock.lang.Specification

/**
 * FR2 of harden-logging-observability, scenario "Claim is the first correlated line": the serve
 * feed's claim path emits the claim anchor <em>before</em> the slot starts working the task.
 *
 * <p>Ordering is the whole point, so it is what the spec measures: the fake slot runner records
 * how many anchor lines exist at the moment it is entered. Every engine event of that task is
 * emitted from inside that slot, so an anchor already present when the slot begins is an anchor
 * that precedes all of them — asserting against the anchor's own timestamp instead would pass on
 * a millisecond-granularity tie.
 */
class ClaimAnchorSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')

    private LogCaptureSupport capture

    def setup() {
        capture = LogCaptureSupport.attach(AnchorLog)
    }

    def cleanup() {
        capture.detach()
    }

    def "the claim anchor is logged before the slot starts working the claimed task"() {
        given: 'a ledger with both permits free, one of them reserved by the caller as the feed does'
        def ledger = new SlotLedger(2)
        ledger.acquire()

        and: 'a tracker that grants the claim'
        Tracker tracker = [claim: { TaskRef ref, String instance ->
                new ClaimResult.Acquired(new ClaimEpoch(1))
            }] as Tracker

        and: 'a slot runner that records the anchors already emitted when it is entered'
        def anchorsAtSlotStart = new AtomicInteger(-1)
        def slotEntered = new CountDownLatch(1)
        SlotRunner runner = { TaskRef ref ->
            anchorsAtSlotStart.set(capture.list.size())
            slotEntered.countDown()
        } as SlotRunner

        when:
        cycle(tracker, ledger, runner).claimOrAbandon([ready('task-9')])

        then: 'the slot ran'
        slotEntered.await(5, TimeUnit.SECONDS)

        and: 'and it found the claim anchor already written'
        anchorsAtSlotStart.get() == 1

        and: 'the anchor is an INFO line naming the task and the occupancy the claim left behind'
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage == 'claim acquired for task task-9: 1 of 2 slot(s) free'

        // FR8, UX2: written under the task's own MDC, though the feed thread owns no task of its
        // own — without it the first line of a task's story is the one line a grep by taskId misses.
        and: 'the anchor carries the task\'s own grep key'
        capture.list[0].MDCPropertyMap.get(MdcAwareThread.TASK_ID_KEY) == 'task-9'
    }

    def "the feed thread carries no task context away from the claim it just announced"() {
        given:
        def ledger = new SlotLedger(2)
        ledger.acquire()
        Tracker tracker = [claim: { TaskRef ref, String instance ->
                new ClaimResult.Acquired(new ClaimEpoch(1))
            }] as Tracker

        when: 'the claim is announced and the slot launched, all on this thread'
        cycle(tracker, ledger, { TaskRef ref -> } as SlotRunner).claimOrAbandon([ready('task-9')])

        then: 'the scope closed with it: the next cycle\'s lines belong to no task (FR8, leak-free MDC)'
        MDC.get(MdcAwareThread.TASK_ID_KEY) == null
    }

    private static ReadyTask ready(String id) {
        // returned() == true short-circuits the open-front gate: this spec is about anchor
        // ordering, not about eligibility (FeedCycleSpec owns that).
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true, false, 'fixture title')
    }

    private static FeedCycle cycle(Tracker tracker, SlotLedger ledger, SlotRunner runner) {
        def outageRetry = new FeedOutageRetry(new BudgetedVirtualSleeper(new VirtualClock()), {
            Duration.ofSeconds(1)
        }, RepeatSuppressorFixture.quiet())
        new FeedCycle(new FeedTracker(tracker, INSTANCE), ledger, runner,
                new FeedSelection(Duration.ofMinutes(2), Duration.ofHours(1), 2, new Random(0)),
                new FeedStateLogger(), outageRetry, new FinishedDecline())
    }
}
