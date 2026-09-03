package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * AbortHandler: the best-effort infrastructure-abort protocol shared by both
 * abort triggers (design D3, D10) — an engine Aborted outcome and an uncaught
 * run exception. Covers the K-fuse threshold decision, the best-effort
 * recordAbort write that never propagates a tracker failure, and that both
 * trigger shapes reach the identical decision through the single handle entry
 * point.
 *
 * Implements FR14, NFR-R2, NFR-C1 of add-tracker-port.
 */
class AbortHandlerSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final Clock CLOCK = Clock.fixed(Instant.parse('2026-07-17T10:00:00Z'), ZoneOffset.UTC)
    private static final int THRESHOLD = 3

    private Tracker tracker = Mock()
    private AbortHandler handler = new AbortHandler(tracker, CLOCK)

    // FR14, NFR-R2: below the fuse, recordAbort is called with the correct
    // AbortRecord, park is never called, and the result is Aborted
    def "an abort below the fuse records the abort and returns Aborted"() {
        given: 'a prior count two below the threshold of 3'
        def facts = new AbortFacts(0, null)
        def logs = LogCaptureSupport.attach(AbortHandler)

        when:
        def result = handler.handle(REF, STATE, 'connection reset', facts, THRESHOLD, INSTANCE)

        then:
        1 * tracker.recordAbort(REF, new AbortRecord('connection reset', INSTANCE.value(), CLOCK.instant()))
        0 * tracker.park(*_)
        result == new TakeResult.Aborted(STATE, 'connection reset')

        and: 'FR15 of harden-logging-observability: every abort announces itself as a coded ERROR naming the task'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.INFRASTRUCTURE_ABORT.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // FR14, NFR-C1: the fuse trips when count + 1 reaches the threshold, parking
    // AwaitingHuman(INFRA) with a report carrying count/cause/threshold, and
    // recordAbort is never called
    def "an abort that reaches the threshold trips the fuse and parks INFRA"() {
        given: 'a prior count that reaches the threshold once incremented'
        def facts = new AbortFacts(THRESHOLD - 1, Instant.parse('2026-07-17T09:00:00Z'))

        when:
        def result = handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then:
        0 * tracker.recordAbort(*_)
        1 * tracker.park(REF, ParkReason.INFRA, { String report ->
            report.contains('disk full') && report.contains("$THRESHOLD") && report.contains('3')
        })
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        (result as TakeResult.AwaitingHuman).report().contains('disk full')
    }

    // FR14: the fuse report carries the abort history, not just the last cause —
    // the count/threshold streak, the previous abort's timestamp from AbortFacts,
    // and a pointer to the per-abort entries where every cause is recorded
    def "the fuse report narrates the abort history, not only the last cause"() {
        given: 'a prior abort recorded an hour earlier, reaching the threshold once incremented'
        def previousAbortAt = Instant.parse('2026-07-17T09:00:00Z')
        def facts = new AbortFacts(THRESHOLD - 1, previousAbortAt)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then: 'the report carries the streak count, the threshold, the prior abort time, and points to the per-abort entries'
        captured.contains('3 consecutive automatic attempts')
        captured.contains('3 crashed runs')
        captured.contains('0 failed branch repairs')
        captured.contains("$THRESHOLD")
        captured.toLowerCase().contains('threshold')
        captured.contains(previousAbortAt.toString())
        captured.toLowerCase().contains('abort entries')
        captured.contains('disk full')
    }

    // FR14: when the facts carry no prior-abort timestamp (count/timestamp
    // pairing is an adapter guarantee, not enforced on the read side), the report
    // omits the "previous abort" clause but still narrates count/threshold/cause
    def "the fuse report omits the prior-abort time when the facts have none"() {
        given: 'facts at the threshold but with a null lastAbortAt'
        def facts = new AbortFacts(THRESHOLD - 1, null)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then:
        !captured.toLowerCase().contains('previous abort')
        captured.contains("$THRESHOLD")
        captured.toLowerCase().contains('threshold')
        captured.contains('disk full')
    }

    // FR14, NFR-O2 of harden-task-branch-contract: both categories spend the one counter and trip
    // the one threshold, and the report attributes the streak to the two causes distinctly
    def "a failed repair spends the same counter and is reported as its own category"() {
        given: 'two crashes already on record, so this attempt reaches the threshold'
        def facts = new AbortFacts(THRESHOLD - 1, Instant.parse('2026-07-17T09:00:00Z'), 0)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        def result = handler.handle(REF, STATE, 'reconcile failed', facts, THRESHOLD, INSTANCE,
                RecoveryCause.RECOVERY_FAILURE)

        then: 'the same threshold trips, and the report splits the streak by cause'
        result instanceof TakeResult.AwaitingHuman
        captured.contains('3 consecutive automatic attempts')
        captured.contains('2 crashed runs')
        captured.contains('1 failed branch repairs')
        captured.contains('recovery_failure')
    }

    // FR14 of harden-task-branch-contract: below the fuse, the category rides into the marker so
    // the next instance reconstructs the streak with its causes intact
    def "a failed repair below the fuse records its category on the marker"() {
        when:
        handler.handle(REF, STATE, 'reconcile failed', AbortFacts.none(), THRESHOLD, INSTANCE,
                RecoveryCause.RECOVERY_FAILURE)

        then:
        1 * tracker.recordAbort(REF, new AbortRecord(
                        'reconcile failed', INSTANCE.value(), CLOCK.instant(), RecoveryCause.RECOVERY_FAILURE))
    }

    // NFR-R2: "a dead tracker never blocks the abort itself" applies at the fuse
    // too — a park failure is caught, logged, and does not propagate; the handler
    // still returns AwaitingHuman(INFRA) so the run stops for a human
    def "a park failure at the fuse does not propagate and still returns AwaitingHuman(INFRA)"() {
        given: 'a fuse-tripping abort against a tracker whose park call is unreachable'
        def facts = new AbortFacts(THRESHOLD - 1, Instant.parse('2026-07-17T09:00:00Z'))
        tracker.park(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(AbortHandler)

        when:
        def result = handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then:
        noExceptionThrown()
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        (result as TakeResult.AwaitingHuman).report().contains('disk full')

        and: 'FR15: the tracker never learned the task is parked, so the swallow leaves a coded ERROR'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ABORT_PARK_FAILED.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // NFR-R2: "a dead tracker never blocks the abort itself" — a recordAbort
    // failure below the fuse is caught, logged, and does not propagate; the
    // handler still returns Aborted
    def "a recordAbort failure below the fuse does not propagate and still returns Aborted"() {
        given: 'a tracker whose recordAbort call is unreachable'
        def facts = AbortFacts.none()
        tracker.recordAbort(*_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(AbortHandler)

        when:
        def result = handler.handle(REF, STATE, 'tracker down', facts, THRESHOLD, INSTANCE)

        then:
        noExceptionThrown()
        result == new TakeResult.Aborted(STATE, 'tracker down')

        and: 'FR15: the unrecorded attempt under-counts the fuse, so the swallow leaves a coded ERROR'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.ABORT_RECORD_FAILED.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()
    }

    // FR10 of add-claim-heartbeat: the abort path stays best-effort — a tracker OUTAGE
    // (TrackerUnavailableException, the same signal finish/park now retry for ~10 min) must NOT
    // trigger a retry loop here. recordAbort is attempted exactly once, then the handler returns
    // Aborted promptly; a dead tracker never blocks the abort itself.
    def "an abort during a tracker outage does not retry-loop: recordAbort is attempted once"() {
        given: 'a tracker whose recordAbort reports an outage'
        def facts = AbortFacts.none()

        when:
        def result = handler.handle(REF, STATE, 'tracker outage', facts, THRESHOLD, INSTANCE)

        then: 'recordAbort is called exactly once — no bounded retry loop on the abort path'
        1 * tracker.recordAbort(*_) >> {
            throw new TrackerUnavailableException('tracker unreachable')
        }
        noExceptionThrown()
        result == new TakeResult.Aborted(STATE, 'tracker outage')
    }

    // D3: both abort triggers (engine Aborted outcome, uncaught run exception)
    // funnel through the identical single entry point and reach the same fuse
    // decision for equal inputs
    def "both abort-trigger shapes reach the identical fuse decision through handle"() {
        given: 'one cause standing in for an engine Aborted outcome, one for an uncaught exception'
        def facts = new AbortFacts(0, null)

        when: 'the engine-outcome-derived cause is handled'
        def fromEngineOutcome = handler.handle(REF, STATE, 'persist failed: disk full', facts, THRESHOLD, INSTANCE)

        and: 'an uncaught-exception-derived cause is handled the same way'
        def fromUncaughtException =
                handler.handle(REF, STATE, 'uncaught: NullPointerException', facts, THRESHOLD, INSTANCE)

        then: 'both reach the below-fuse Aborted branch via the same protocol'
        2 * tracker.recordAbort(*_)
        fromEngineOutcome instanceof TakeResult.Aborted
        fromUncaughtException instanceof TakeResult.Aborted
    }
}
