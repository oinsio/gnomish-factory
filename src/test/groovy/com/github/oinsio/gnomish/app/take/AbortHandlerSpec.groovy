package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.TaskState
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

        when:
        def result = handler.handle(REF, STATE, 'connection reset', facts, THRESHOLD, INSTANCE)

        then:
        1 * tracker.recordAbort(REF, new AbortRecord('connection reset', INSTANCE.value(), CLOCK.instant()))
        0 * tracker.park(*_)
        result == new TakeResult.Aborted(STATE, 'connection reset')
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
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { args -> captured = args[2] }

        when:
        handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then: 'the report carries the streak count, the threshold, the prior abort time, and points to the per-abort entries'
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
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { args -> captured = args[2] }

        when:
        handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then:
        !captured.toLowerCase().contains('previous abort')
        captured.contains("$THRESHOLD")
        captured.toLowerCase().contains('threshold')
        captured.contains('disk full')
    }

    // NFR-R2: "a dead tracker never blocks the abort itself" applies at the fuse
    // too — a park failure is caught, logged, and does not propagate; the handler
    // still returns AwaitingHuman(INFRA) so the run stops for a human
    def "a park failure at the fuse does not propagate and still returns AwaitingHuman(INFRA)"() {
        given: 'a fuse-tripping abort against a tracker whose park call is unreachable'
        def facts = new AbortFacts(THRESHOLD - 1, Instant.parse('2026-07-17T09:00:00Z'))
        tracker.park(*_) >> { throw new RuntimeException('tracker unreachable') }

        when:
        def result = handler.handle(REF, STATE, 'disk full', facts, THRESHOLD, INSTANCE)

        then:
        noExceptionThrown()
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA
        (result as TakeResult.AwaitingHuman).report().contains('disk full')
    }

    // NFR-R2: "a dead tracker never blocks the abort itself" — a recordAbort
    // failure below the fuse is caught, logged, and does not propagate; the
    // handler still returns Aborted
    def "a recordAbort failure below the fuse does not propagate and still returns Aborted"() {
        given: 'a tracker whose recordAbort call is unreachable'
        def facts = AbortFacts.none()
        tracker.recordAbort(*_) >> { throw new RuntimeException('tracker unreachable') }

        when:
        def result = handler.handle(REF, STATE, 'tracker down', facts, THRESHOLD, INSTANCE)

        then:
        noExceptionThrown()
        result == new TakeResult.Aborted(STATE, 'tracker down')
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
