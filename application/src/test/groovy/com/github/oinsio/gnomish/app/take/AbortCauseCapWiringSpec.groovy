package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * AbortHandler's cause-budget choke point: everything tracker-bound — the
 * recordAbort marker and the fuse-trip park report — passes AbortCauseBudget
 * first, while the ERROR log and the returned result keep the full text. Also
 * pins the headroom the budget reserves for the report's own framing, so a
 * framing that outgrows it fails here rather than in production.
 *
 * FR1, NFR-R1, NFR-O1 of cap-abort-cause-length.
 */
class AbortCauseCapWiringSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final Clock CLOCK = Clock.fixed(Instant.parse('2026-07-17T10:00:00Z'), ZoneOffset.UTC)
    private static final int THRESHOLD = 3

    private Tracker tracker = Mock()
    private AbortHandler handler = new AbortHandler(tracker, CLOCK)

    // FR1, NFR-O1 of cap-abort-cause-length: an over-budget cause reaches the
    // tracker's abort marker already capped, while the returned result keeps the full text
    def "an over-budget cause is capped before the recordAbort write"() {
        given: 'a cause far past every tracker comment limit'
        def cause = 'persist failed\n' + ('f' * 200_000) + '\nCaused by: disk full'
        def logs = LogCaptureSupport.attach(AbortHandler)
        AbortRecord captured = null
        tracker.recordAbort(REF, _ as AbortRecord) >> { TaskRef ref, AbortRecord record ->
            captured = record
        }

        when:
        def result = handler.handle(REF, STATE, UntrustedText.subprocess(cause), new AbortFacts(0, null), THRESHOLD, INSTANCE)

        then: 'the marker carries the capped cause: bounded, head and tail kept, omission named'
        captured.cause().length() <= AbortCauseBudget.BUDGET_CHARS
        captured.cause().raw().startsWith('persist failed')
        captured.cause().raw().endsWith('Caused by: disk full')
        captured.cause().contains('characters omitted')

        and: 'the returned result keeps the full diagnostic text — the bound is the tracker\'s'
        result == new TakeResult.Aborted(STATE, UntrustedText.subprocess(cause))

        // The log plane has its own bound, and it is not the tracker's (rules/logging.md,
        // design D5 of type-untrusted-text).
        and: 'while the ERROR log carries the cause through the log exit'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.INFRASTRUCTURE_ABORT.head())
        }
        event.formattedMessage.contains(UntrustedText.subprocess(cause).forLog())

        cleanup:
        logs.detach()
    }

    // FR1, NFR-R1 of cap-abort-cause-length: the fuse-trip park report carries the
    // capped cause, so an oversized cause cannot cost the park write its body
    def "an over-budget cause is capped before the fuse-trip park report"() {
        given:
        def cause = 'persist failed\n' + ('f' * 200_000) + '\nCaused by: disk full'
        def facts = new AbortFacts(THRESHOLD - 1, null)
        def logs = LogCaptureSupport.attach(AbortHandler)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        def result = handler.handle(REF, STATE, UntrustedText.subprocess(cause), facts, THRESHOLD, INSTANCE)

        then:
        captured.contains('characters omitted')
        !captured.contains(cause)
        captured.contains('Caused by: disk full')
        (result as TakeResult.AwaitingHuman).report() == captured

        cleanup:
        logs.detach()
    }

    // NFR-R1 of cap-abort-cause-length: the finished park report — the capped
    // cause plus the builder's own framing — stays inside the smallest supported
    // tracker comment limit (Jira Cloud, 32_767 characters), which is the headroom
    // invariant of design D2
    def "the complete fuse-trip report fits the smallest tracker comment limit"() {
        given: 'a maximal cause and the wordiest framing the builder can produce'
        def facts = new AbortFacts(999_999, Instant.parse('2026-07-17T09:00:00Z'), 999_998)
        def logs = LogCaptureSupport.attach(AbortHandler)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        handler.handle(REF, STATE, UntrustedText.subprocess('z' * 500_000), facts, 1_000_000, INSTANCE, RecoveryCause.RECOVERY_FAILURE)

        then:
        captured.length() < 32_767

        cleanup:
        logs.detach()
    }

    // FR1 of cap-abort-cause-length: a cause within the budget reaches both
    // tracker writes byte-for-byte, with no marker introduced
    def "a within-budget cause reaches the tracker writes unchanged"() {
        given:
        def cause = 'connection reset by peer'
        def logs = LogCaptureSupport.attach(AbortHandler)
        String parked = null
        AbortRecord recorded = null
        tracker.recordAbort(REF, _ as AbortRecord) >> { TaskRef ref, AbortRecord record ->
            recorded = record
        }
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            parked = report
        }

        when: 'the same cause aborts below the fuse and at it'
        handler.handle(REF, STATE, UntrustedText.subprocess(cause), new AbortFacts(0, null), THRESHOLD, INSTANCE)
        handler.handle(REF, STATE, UntrustedText.subprocess(cause), new AbortFacts(THRESHOLD - 1, null), THRESHOLD, INSTANCE)

        then:
        // The marker carries the carrier itself now (task 6.3): the adapter that publishes it is
        // the one that renders it, so "unchanged" is the plain identity of the value here.
        recorded.cause() == UntrustedText.subprocess(cause)
        parked.contains(cause)
        !parked.contains('characters omitted')

        cleanup:
        logs.detach()
    }
}
