package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR4 / UX3 of harden-logging-observability: a tracker outage lasting hours is one fault, and the
 * beat thread must say so as edges — first occurrence, periodic counted roll-ups, one recovery —
 * rather than one WARN per beat interval per held claim.
 *
 * <p>{@link HeartbeatBeater} and {@link HeartbeatTickLog} are driven directly here because the
 * roll-up only fires once the quiet period has elapsed: both take the suppressor as a component,
 * so a {@link VirtualClock} makes minutes of outage instant. {@link BeatFailureTaxonomySpec}
 * covers the same edges through the assembled {@link InstanceHeartbeat}; the last feature drives
 * the assembled heartbeat too, to show its own suppressor runs on the source it was given (FR19 of
 * supervise-daemon-loops-and-embed-dashboard).
 */
class HeartbeatOutageSuppressionSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final Duration BEAT = Duration.ofMinutes(5)

    /** The one virtual time source of every feature: beats, alive-at stamps and the suppressor. */
    VirtualClock clock = new VirtualClock(Instant.parse('2026-09-03T10:00:00Z'))
    /** The production shape: a roll-up period several beats long, so repeats really are quiet. */
    RepeatSuppressor suppressor = new RepeatSuppressor(clock, BEAT.multipliedBy(6))

    // FR4: an outage spanning many beats is announced once, reminded periodically, and closed once.
    def "a sustained beat outage is edges, not one WARN per beat"() {
        given:
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { throw new RuntimeException('5xx') }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)

        when: 'the tracker is down across an hour of beats'
        12.times {
            beater.beat(A, clock.instant())
            clock.advance(BEAT)
        }

        then: 'the outage arrives once'
        logs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        } == 1

        and: 'and is reminded of periodically, with a count, at the site level'
        def rollUps = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILING_ROLLUP.head())
        }
        !rollUps.isEmpty()
        rollUps.every {
            it.level == Level.WARN && it.formattedMessage.contains(A.id())
        }

        and: 'every beat in between is DEBUG, so the operator plane stays quiet'
        logs.list.count { it.level == Level.WARN } < 12

        cleanup:
        logs.detach()
    }

    // FR4: a different fault mid-outage is news, so it restarts the streak instead of being hidden.
    def "a changed fault during an outage is announced again"() {
        given:
        def faults = [
            '5xx',
            '5xx',
            'connection reset'
        ].iterator()
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { throw new RuntimeException(faults.next()) }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)

        when:
        3.times { beater.beat(A, clock.instant()) }

        then:
        logs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        } == 2

        cleanup:
        logs.detach()
    }

    // FR4: releasing a claim mid-outage leaves no streak behind for the life of the process.
    def "forgetting a claim ends its streak, so the next outage is announced afresh"() {
        given:
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { throw new RuntimeException('5xx') }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)

        when:
        beater.beat(A, clock.instant())
        beater.forget(A)
        beater.beat(A, clock.instant())

        then:
        logs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        } == 2

        cleanup:
        logs.detach()
    }

    // FR4: the tick's own streak has the same three edges, under its own key and its own codes.
    def "a sustained tick failure is edges too"() {
        given:
        def tickLog = new HeartbeatTickLog(suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatTickLog, Level.DEBUG)

        when: 'a tick keeps failing across an hour, then completes'
        12.times {
            tickLog.failed(new IllegalStateException('sink threw'))
            clock.advance(BEAT)
        }
        tickLog.recovered()

        then:
        logs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_TICK_FAILED.head())
        } == 1

        and:
        def rollUps = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_TICK_FAILING_ROLLUP.head())
        }
        !rollUps.isEmpty()
        rollUps.every { it.level == Level.WARN }

        and: 'the recovery closes it at INFO'
        logs.list.any {
            it.level == Level.INFO && it.formattedMessage.contains('tick recovered')
        }

        cleanup:
        logs.detach()
    }

    // FR4: a claim that beats again after an outage closes it, and the beat itself is unchanged.
    def "a recovered beat closes the outage and still reports BEATEN"() {
        given:
        def answers = [
            null,
            new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))
        ].iterator()
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> {
            def next = answers.next()
            if (next == null) {
                throw new RuntimeException('5xx')
            }
            next
        }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater)

        when:
        beater.beat(A, clock.instant())
        def outcome = beater.beat(A, clock.instant())

        then:
        outcome == BeatOutcome.BEATEN
        logs.list.any {
            it.level == Level.INFO && it.formattedMessage.contains('beat recovered')
        }

        cleanup:
        logs.detach()
    }

    // FR4: two claims are two subjects — a fault on one must not silence the other's first word.
    def "each claim's streak is its own, so a second claim's outage is announced too"() {
        given:
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { throw new RuntimeException('5xx') }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)
        def b = new TaskRef('github:o/r#2')

        when:
        beater.beat(A, clock.instant())
        beater.beat(b, clock.instant())

        then: 'both are first occurrences, each naming its own task'
        def announced = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        }
        announced.size() == 2
        announced*.formattedMessage.any { it.contains(A.id()) }
        announced*.formattedMessage.any { it.contains(b.id()) }

        cleanup:
        logs.detach()
    }

    // FR4: a fault carrying no words is still identified, and never reads as a literal "null".
    def "a beat fault with no message is announced by its type alone"() {
        given:
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { throw new RuntimeException() }
        def beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatBeater)

        when:
        beater.beat(A, clock.instant())

        then:
        def announced = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        }
        announced.formattedMessage.contains('java.lang.RuntimeException')
        !announced.formattedMessage.contains('null')

        cleanup:
        logs.detach()
    }

    // FR4: the same, for the tick streak — its reason comes from the same shared identity.
    def "a tick fault with no message is announced by its type alone"() {
        given:
        def tickLog = new HeartbeatTickLog(suppressor)
        def logs = LogCaptureSupport.attach(HeartbeatTickLog)

        when:
        tickLog.failed(new IllegalStateException())

        then:
        def announced = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_TICK_FAILED.head())
        }
        announced.formattedMessage.contains('java.lang.IllegalStateException')
        !announced.formattedMessage.contains('null')

        cleanup:
        logs.detach()
    }

    // FR19: the heartbeat builds its suppressor on its own injected time source, the one its
    //     alive-at stamps come from — so an outage measured across a roll-up period on a virtual
    //     source rolls up and recovers without any real time passing. On a second, real clock the
    //     streak would span a few real milliseconds: no roll-up, and a recovery over ~PT0S. The
    //     beater holds no clock (task 3.9): the tick hands it the instant it read, so the
    //     heartbeat's one source is the only one a beat or its suppressor can reach.
    def "the assembled heartbeat's suppression runs on the heartbeat's own time source"() {
        given: 'a tracker down for the first twelve beats, then answering'
        def payloads = []
        def beats = 0
        def tracker = Stub(Tracker)
        tracker.heartbeat(_, _) >> { TaskRef ref, String payload ->
            payloads << payload
            if (++beats <= 12) {
                throw new RuntimeException('5xx')
            }
            new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))
        }
        def heartbeat = new InstanceHeartbeat(tracker, new HeartbeatProgress(), VirtualTimeEquipment.on(clock, { ignored -> } as Sleeper),
        new BeatTiming(BEAT, BEAT.multipliedBy(100)), { ignored -> } as ClaimLostSink, HeartbeatStateListener.IGNORE)
        heartbeat.seedHeldForTest(A)
        def logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)

        when: 'twelve failing beats, one beat interval apart on the virtual source, then one that lands'
        12.times {
            heartbeat.tick()
            clock.advance(BEAT)
        }
        heartbeat.tick()

        then: 'the outage rolled up on the virtual source: its roll-up period is six beats (RollUpPeriod)'
        def rollUps = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILING_ROLLUP.head())
        }
        !rollUps.isEmpty()
        rollUps.every {
            it.level == Level.WARN && it.formattedMessage.contains(A.id())
        }

        and: 'the recovery measures the outage on the same source: twelve beat intervals, one hour'
        logs.list.any {
            it.level == Level.INFO && it.formattedMessage.contains('beat recovered') &&
            it.formattedMessage.contains("over ${BEAT.multipliedBy(12)}")
        }

        and: 'the alive-at stamps come from that very source, read once per tick'
        payloads.last().endsWith("alive-at=${clock.instant()}")

        cleanup:
        logs.detach()
    }
}
