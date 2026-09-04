package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
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
    // tracker's abort marker already capped, while the ERROR log keeps the full text
    def "an over-budget cause is capped before the recordAbort write"() {
        given: 'a cause far past every tracker comment limit'
        def cause = 'persist failed\n' + ('f' * 200_000) + '\nCaused by: disk full'
        def logs = LogCaptureSupport.attach(AbortHandler)
        AbortRecord captured = null
        tracker.recordAbort(REF, _ as AbortRecord) >> { TaskRef ref, AbortRecord record ->
            captured = record
        }

        when:
        def result = handler.handle(REF, STATE, cause, new AbortFacts(0, null), THRESHOLD, INSTANCE)

        then: 'the marker carries the capped cause: bounded, head and tail kept, omission named'
        captured.cause().length() <= AbortCauseBudget.BUDGET_CHARS
        captured.cause().startsWith('persist failed')
        captured.cause().endsWith('Caused by: disk full')
        captured.cause().contains('characters omitted')

        and: 'the ERROR log and the returned result keep the full diagnostic text'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.INFRASTRUCTURE_ABORT.head())
        }
        event.formattedMessage.contains(cause)
        result == new TakeResult.Aborted(STATE, cause)

        cleanup:
        logs.detach()
    }

    // FR1, NFR-R1 of cap-abort-cause-length: the fuse-trip park report carries the
    // capped cause, so an oversized cause cannot cost the park write its body
    def "an over-budget cause is capped before the fuse-trip park report"() {
        given:
        def cause = 'persist failed\n' + ('f' * 200_000) + '\nCaused by: disk full'
        def facts = new AbortFacts(THRESHOLD - 1, null)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        def result = handler.handle(REF, STATE, cause, facts, THRESHOLD, INSTANCE)

        then:
        captured.contains('characters omitted')
        !captured.contains(cause)
        captured.contains('Caused by: disk full')
        (result as TakeResult.AwaitingHuman).report() == captured
    }

    // NFR-R1 of cap-abort-cause-length: the finished park report — the capped
    // cause plus the builder's own framing — stays inside the smallest supported
    // tracker comment limit (Jira Cloud, 32_767 characters), which is the headroom
    // invariant of design D2
    def "the complete fuse-trip report fits the smallest tracker comment limit"() {
        given: 'a maximal cause and the wordiest framing the builder can produce'
        def facts = new AbortFacts(999_999, Instant.parse('2026-07-17T09:00:00Z'), 999_998)
        String captured = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            captured = report
        }

        when:
        handler.handle(REF, STATE, 'z' * 500_000, facts, 1_000_000, INSTANCE, RecoveryCause.RECOVERY_FAILURE)

        then:
        captured.length() < 32_767
    }

    // FR1 of cap-abort-cause-length: a cause within the budget reaches both
    // tracker writes byte-for-byte, with no marker introduced
    def "a within-budget cause reaches the tracker writes unchanged"() {
        given:
        def cause = 'connection reset by peer'
        String parked = null
        AbortRecord recorded = null
        tracker.recordAbort(REF, _ as AbortRecord) >> { TaskRef ref, AbortRecord record ->
            recorded = record
        }
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { TaskRef ref, ParkReason reason, String report ->
            parked = report
        }

        when: 'the same cause aborts below the fuse and at it'
        handler.handle(REF, STATE, cause, new AbortFacts(0, null), THRESHOLD, INSTANCE)
        handler.handle(REF, STATE, cause, new AbortFacts(THRESHOLD - 1, null), THRESHOLD, INSTANCE)

        then:
        recorded.cause() == cause
        parked.contains(cause)
        !parked.contains('characters omitted')
    }
}
