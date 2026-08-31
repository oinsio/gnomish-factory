package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * EpochRecordingTracker: keeps the instance's ClaimEpochBook in step with the tenures it holds,
 * by watching the one place a tenure can begin or end — the tracker port (FR13 of
 * harden-task-branch-contract).
 */
class EpochRecordingTrackerSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')

    static final ClaimVersion VERSION = new ClaimVersion('42', Instant.EPOCH, new ClaimEpoch(42))

    def delegate = Mock(Tracker)
    def book = new ClaimEpochBook()
    def tracker = new EpochRecordingTracker(delegate, book)

    // FR13: the epoch is recorded before the caller can make its first write of the tenure
    def "records the epoch a successful claim was issued"() {
        when:
        def result = tracker.claim(REF, 'gnomish-a-1')

        then:
        1 * delegate.claim(REF, 'gnomish-a-1') >> new ClaimResult.Acquired(new ClaimEpoch(42))
        result == new ClaimResult.Acquired(new ClaimEpoch(42))
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(42)
    }

    // FR13: a lost race is not a tenure — nothing to stamp, nothing to record
    def "records nothing when the claim was held by another instance"() {
        when:
        def result = tracker.claim(REF, 'gnomish-a-1')

        then:
        1 * delegate.claim(REF, 'gnomish-a-1') >> new ClaimResult.Held('gnomish-b-2')
        result == new ClaimResult.Held('gnomish-b-2')
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: dropping the claim forgets it, so no later write stamps an epoch we no longer hold —
    //     and the write itself still reaches the live tracker, unchanged
    def "forgets the tenure when the claim ends by release"() {
        given:
        holdTenure()

        when:
        tracker.release(REF)

        then:
        1 * delegate.release(REF)
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: a terminal write ends the claim on the tracker but NOT the tenure here — its receipt
    //     and destructive-step commits run behind the confirmed write and must carry the epoch, so
    //     the run-scoped choke point (TakeClaimAndWork#dispatchAfterClaim's finally) ends it after
    //     those commits. Forgetting here left the last commit of every tenure outside the fence.
    def "keeps the tenure across #operation, whose branch-side commits still belong to it"() {
        given:
        holdTenure()

        when:
        write.call(tracker)

        then:
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(42)

        where:
        operation | write
        'recordAbort' | { Tracker t ->
            t.recordAbort(REF, new AbortRecord('infra', 'gnomish-a-1', Instant.EPOCH))
        }
        'park' | { Tracker t ->
            t.park(REF, ParkReason.ESCALATION, 'report')
        }
        'finish' | { Tracker t -> t.finish(REF, 'summary') }
    }

    def "forwards the terminal writes to the live tracker unchanged"() {
        given:
        holdTenure()
        def record = new AbortRecord('infra', 'gnomish-a-1', Instant.EPOCH)

        when:
        tracker.recordAbort(REF, record)
        tracker.park(REF, ParkReason.ESCALATION, 'report')
        tracker.finish(REF, 'summary')

        then:
        1 * delegate.recordAbort(REF, record)
        1 * delegate.park(REF, ParkReason.ESCALATION, 'report')
        1 * delegate.finish(REF, 'summary')
    }

    /** Puts this instance in a live tenure on REF at epoch 42, as a successful claim would. */
    private void holdTenure() {
        delegate.claim(REF, 'gnomish-a-1') >> new ClaimResult.Acquired(new ClaimEpoch(42))
        tracker.claim(REF, 'gnomish-a-1')
    }

    // FR13: a beat that reports the claim gone ends the tenure just as a release does
    def "forgets the tenure when a beat reports the claim gone"() {
        given:
        delegate.claim(REF, 'gnomish-a-1') >> new ClaimResult.Acquired(new ClaimEpoch(42))
        delegate.heartbeat(REF, 'progress') >> new HeartbeatResult.ClaimGone()
        tracker.claim(REF, 'gnomish-a-1')

        when:
        tracker.heartbeat(REF, 'progress')

        then:
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: an ordinary beat leaves the tenure exactly where it was — it is the same claim
    def "keeps the tenure across an ordinary beat"() {
        given:
        delegate.claim(REF, 'gnomish-a-1') >> new ClaimResult.Acquired(new ClaimEpoch(42))
        delegate.heartbeat(REF, 'progress') >> new HeartbeatResult.Beaten(
                new ClaimVersion('42', Instant.EPOCH, new ClaimEpoch(42)))
        tracker.claim(REF, 'gnomish-a-1')

        when:
        tracker.heartbeat(REF, 'progress')

        then:
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(42)
    }

    // FR13: the decorator is transparent — every non-claim operation reaches the live tracker, and
    //     the caller receives exactly what the tracker answered, never a substitute
    private static final ClaimFacts CLAIM = new ClaimFacts.Live('gnomish-a-1', VERSION)

    private static final TrackerFacts FACTS = TrackerFacts.of(StateLabels.workingOnly(), CLAIM)

    def "forwards the reading operations and returns the tracker's own answers"() {
        given:
        def ready = [
            new ReadyTask(REF, AbortFacts.none(), false, false, 'title')
        ]
        def task = new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)
        def replies = [
            new HumanReply('do it', Instant.EPOCH)
        ]
        def open = [
            new OpenTask(REF, new TrackerTaskState.Working('gnomish-a-1'), VERSION, 'title')
        ]
        def mismatch = new RemoveStaleClaimResult.Mismatch(VERSION)
        def repaired = new RepairIndexResult.Repaired(FACTS)

        when:
        def readyResult = tracker.listReady(3)
        def taskResult = tracker.fetchTask(REF)
        def replyResult = tracker.collectDecisions(REF)
        def openResult = tracker.listOpen()
        def removalResult = tracker.removeStaleClaim(REF, CLAIM)
        def repairResult = tracker.repairIndex(REF, FACTS)
        def beat = tracker.heartbeat(REF, 'progress')

        then:
        1 * delegate.listReady(3) >> ready
        1 * delegate.fetchTask(REF) >> task
        1 * delegate.collectDecisions(REF) >> replies
        1 * delegate.listOpen() >> open
        1 * delegate.removeStaleClaim(REF, CLAIM) >> mismatch
        1 * delegate.repairIndex(REF, FACTS) >> repaired
        1 * delegate.heartbeat(REF, 'progress') >> new HeartbeatResult.Beaten(VERSION)

        and:
        readyResult.is(ready)
        taskResult.is(task)
        replyResult.is(replies)
        openResult.is(open)
        removalResult.is(mismatch)
        repairResult.is(repaired)
        beat == new HeartbeatResult.Beaten(VERSION)
    }

    // FR13: the correspondence operations are pass-through too — none of them ends a tenure
    def "forwards the correspondence operations without touching the tenure"() {
        given:
        holdTenure()

        when:
        tracker.declineFinished(REF, 'message')
        tracker.recordProgress(REF)
        tracker.acknowledgeDecision(REF, 'decision')
        tracker.postNote(REF, 'note')

        then:
        1 * delegate.declineFinished(REF, 'message')
        1 * delegate.recordProgress(REF)
        1 * delegate.acknowledgeDecision(REF, 'decision')
        1 * delegate.postNote(REF, 'note')

        and: 'the tenure is untouched — none of these ends a claim'
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(42)
    }
}
