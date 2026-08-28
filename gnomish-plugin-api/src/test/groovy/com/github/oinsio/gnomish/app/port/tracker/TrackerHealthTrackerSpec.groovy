package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR8, D12 of add-serve-observability: the thin {@link Tracker}-port decorator shared by feed,
 * heartbeat, and reaper. Every delegate call updates {@link TrackerHealthTracker#lastSuccessAt()}/
 * {@link TrackerHealthTracker#consecutiveFailures()} — success resets the streak, a thrown
 * {@link RuntimeException} increments it and is rethrown unchanged, so callers' error handling
 * never sees a different exception.
 */
class TrackerHealthTrackerSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    private Tracker delegate = Mock()
    private VirtualClock clock = new VirtualClock()
    private TrackerHealthTracker tracker = new TrackerHealthTracker(delegate, clock)

    def "before any call, health has no lastSuccessAt and no failures"() {
        expect:
        tracker.lastSuccessAt() == null
        tracker.consecutiveFailures() == 0
    }

    def "a successful call sets lastSuccessAt to the clock's current instant and resets failures"() {
        given:
        clock.advance(Duration.ofSeconds(10))
        delegate.listOpen() >> []

        when:
        tracker.listOpen()

        then:
        tracker.lastSuccessAt() == clock.now()
        tracker.consecutiveFailures() == 0
    }

    def "a failing call increments consecutiveFailures and rethrows the original exception unchanged"() {
        given:
        def failure = new IllegalStateException('tracker unreachable')
        delegate.listOpen() >> { throw failure }

        when:
        tracker.listOpen()

        then:
        def thrown = thrown(IllegalStateException)
        thrown.is(failure)
        tracker.consecutiveFailures() == 1
        tracker.lastSuccessAt() == null
    }

    def "consecutive failures accumulate across calls until a success resets them"() {
        given:
        delegate.listOpen() >> { throw new RuntimeException('boom') }
        delegate.release(REF) >> { throw new RuntimeException('boom') }

        when:
        [tracker, tracker].each {
            try {
                it.listOpen()
            } catch (RuntimeException ignored) {
            }
        }
        try {
            tracker.release(REF)
        } catch (RuntimeException ignored) {
        }

        then:
        tracker.consecutiveFailures() == 3

        when: 'a subsequent call succeeds'
        delegate.postNote(REF, 'note') >> null
        tracker.postNote(REF, 'note')

        then:
        tracker.consecutiveFailures() == 0
        tracker.lastSuccessAt() == clock.now()
    }

    def "alternating success and failure calls track only the current streak"() {
        given:
        delegate.postNote(REF, 'ok') >> null
        delegate.listOpen() >> { throw new RuntimeException('boom') }

        when:
        tracker.postNote(REF, 'ok')

        then:
        tracker.consecutiveFailures() == 0

        when:
        tracker.listOpen()

        then:
        thrown(RuntimeException)
        tracker.consecutiveFailures() == 1

        when:
        tracker.postNote(REF, 'ok')

        then:
        tracker.consecutiveFailures() == 0
    }

    private static final ClaimFacts CLAIM =
    new ClaimFacts.Live('instance', new ClaimVersion('marker-1', Instant.EPOCH, new ClaimEpoch(1)))

    private static final TrackerFacts FACTS = TrackerFacts.of(StateLabels.workingOnly(), CLAIM)

    def "every Tracker operation is delegated"() {
        when:
        tracker.listReady(5)
        tracker.fetchTask(REF)
        tracker.collectDecisions(REF)
        tracker.claim(REF, 'instance')
        tracker.release(REF)
        tracker.park(REF, ParkReason.ESCALATION, 'report')
        tracker.finish(REF, 'summary')
        tracker.recordAbort(REF, new AbortRecord('cause', 'instance', Instant.EPOCH))
        tracker.recordProgress(REF)
        tracker.acknowledgeDecision(REF, 'decision')
        tracker.postNote(REF, 'note')
        tracker.declineFinished(REF, 'message')
        tracker.listOpen()
        tracker.heartbeat(REF, 'progress')
        tracker.removeStaleClaim(REF, CLAIM)
        tracker.repairIndex(REF, FACTS)

        then:
        1 * delegate.listReady(5)
        1 * delegate.fetchTask(REF)
        1 * delegate.collectDecisions(REF)
        1 * delegate.claim(REF, 'instance')
        1 * delegate.release(REF)
        1 * delegate.park(REF, ParkReason.ESCALATION, 'report')
        1 * delegate.finish(REF, 'summary')
        1 * delegate.recordAbort(REF, _ as AbortRecord)
        1 * delegate.recordProgress(REF)
        1 * delegate.acknowledgeDecision(REF, 'decision')
        1 * delegate.postNote(REF, 'note')
        1 * delegate.declineFinished(REF, 'message')
        1 * delegate.listOpen()
        1 * delegate.heartbeat(REF, 'progress')
        1 * delegate.removeStaleClaim(REF, _ as ClaimFacts)
        1 * delegate.repairIndex(REF, _ as TrackerFacts)
    }

    // PIT: every delegating method must return the delegate's ACTUAL value unchanged, not just
    // forward the call — kills "replaced return value with null/Collections.emptyList" mutants on
    // fetchTask, collectDecisions, listOpen, heartbeat, removeStaleClaim.
    def "every delegating operation returns the delegate's actual value, unchanged"() {
        given:
        def fetchedTask = new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Gone(), new AbortFacts(0, null),
                false)
        def decisions = [
            new HumanReply('looks good', Instant.EPOCH)
        ]
        def openTasks = [
            new OpenTask(REF, new TrackerTaskState.Gone(), null, 'fixture title')
        ]
        def heartbeatResult = new HeartbeatResult.Beaten(new ClaimVersion('marker-1', Instant.EPOCH, new ClaimEpoch(1)))
        def removeResult = new RemoveStaleClaimResult.Removed()
        def repairResult = new RepairIndexResult.Repaired(FACTS)
        delegate.fetchTask(REF) >> fetchedTask
        delegate.collectDecisions(REF) >> decisions
        delegate.listOpen() >> openTasks
        delegate.heartbeat(REF, 'progress') >> heartbeatResult
        delegate.removeStaleClaim(REF, _ as ClaimFacts) >> removeResult
        delegate.repairIndex(REF, _ as TrackerFacts) >> repairResult

        expect:
        tracker.fetchTask(REF).is(fetchedTask)
        tracker.collectDecisions(REF).is(decisions)
        tracker.listOpen().is(openTasks)
        tracker.heartbeat(REF, 'progress').is(heartbeatResult)
        tracker.removeStaleClaim(REF, CLAIM).is(removeResult)
        tracker.repairIndex(REF, FACTS).is(repairResult)
    }
}
