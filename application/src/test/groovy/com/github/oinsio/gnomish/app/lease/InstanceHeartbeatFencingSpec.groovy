package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * InstanceHeartbeat's self-fencing half (FR13 of harden-task-branch-contract, claim-heartbeat
 * "Unconfirmed heartbeat freezes writes at the boundary"): a holder whose beats stop reaching the
 * tracker gives up on knowing its claim is live once the lost-detection threshold passes, and says
 * so through the same sink a lost claim travels on — while the beats keep trying, because an outage
 * that ends is not a lost claim.
 */
class InstanceHeartbeatFencingSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration LOST_DETECTION = Duration.ofMinutes(10)
    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final HeartbeatResult BEATEN =
    new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))

    private final Tracker tracker = Mock()
    private final VirtualClock clock = new VirtualClock()
    private final List<TaskRef> lost = []
    private final List<TaskRef> unconfirmed = []
    private final List<TaskRef> confirmed = []

    private final ClaimLostSink sink = new ClaimLostSink() {
        void claimLost(TaskRef ref) {
            lost << ref
        }
        void claimUnconfirmed(TaskRef ref) {
            unconfirmed << ref
        }
        void claimConfirmed(TaskRef ref) {
            confirmed << ref
        }
    }

    private final InstanceHeartbeat hb = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), new BlockingSleeper(), clock, INTERVAL,
    sink, HeartbeatStateListener.IGNORE, LOST_DETECTION)

    def cleanup() {
        hb.unregister(A)
    }

    // FR13: a short outage says nothing yet — the claim is still within its lost-detection window,
    //     so the run keeps writing and the reaper is nowhere near reassigning the task
    def "an outage shorter than the lost-detection threshold does not fence the claim"() {
        given:
        tracker.heartbeat(A, _) >> {
            throw new TrackerUnavailableException('5xx')
        }
        hb.register(A)

        when:
        clock.advance(Duration.ofMinutes(9))
        hb.tick()

        then:
        unconfirmed.isEmpty()
        lost.isEmpty()
    }

    // FR13: past the threshold the holder no longer knows its claim is live, so it surfaces the
    //     freeze — before any reaper could reassign the task
    def "beats failing past the lost-detection threshold fence the claim"() {
        given:
        tracker.heartbeat(A, _) >> {
            throw new TrackerUnavailableException('5xx')
        }
        hb.register(A)
        def logs = LogCaptureSupport.attach(InstanceHeartbeat)

        when:
        clock.advance(LOST_DETECTION)
        hb.tick()

        then:
        unconfirmed == [A]
        lost.isEmpty()

        and: 'FR15 of harden-logging-observability: the freeze is a coded WARN naming the claim it froze'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.CLAIM_UNCONFIRMED_WRITES_FROZEN.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains(A.id())

        cleanup:
        logs.detach()
    }

    // FR13: the fence is not the end of the run — the thread keeps beating, and a beat that lands
    //     confirms the claim again so the freeze lifts
    def "a beat that lands again confirms the claim"() {
        given:
        hb.register(A)
        tracker.heartbeat(A, _) >>> [
            {
                throw new TrackerUnavailableException('5xx')
            },
            BEATEN
        ]

        when: 'the outage outlasts the threshold, then connectivity returns'
        clock.advance(LOST_DETECTION)
        hb.tick()
        hb.tick()

        then:
        unconfirmed == [A]
        confirmed == [A]
    }

    // FR13: the lost-detection clock runs from the last CONFIRMED beat, not from registration —
    //     a claim beaten happily for hours is fenced only after the threshold passes since that beat
    def "the threshold is measured from the last confirmed beat"() {
        given:
        hb.register(A)
        tracker.heartbeat(A, _) >>> [
            BEATEN,
            {
                throw new TrackerUnavailableException('5xx')
            }
        ]

        when: 'a beat lands late in the window, then the tracker goes away for less than the threshold'
        clock.advance(Duration.ofMinutes(9))
        hb.tick()
        clock.advance(Duration.ofMinutes(9))
        hb.tick()

        then: 'no fence: only nine minutes have passed since the claim was last confirmed'
        unconfirmed.isEmpty()
    }

    // FR8: a claim the tracker says is gone is lost, not merely unconfirmed — the two states never
    //     contradict each other, and the lost path ends the run at the same boundary
    def "a claim reported gone is lost rather than fenced"() {
        given:
        tracker.heartbeat(A, _) >> new HeartbeatResult.ClaimGone()
        hb.register(A)

        when:
        clock.advance(LOST_DETECTION)
        hb.tick()

        then:
        lost == [A]
        unconfirmed.isEmpty()
    }
}
