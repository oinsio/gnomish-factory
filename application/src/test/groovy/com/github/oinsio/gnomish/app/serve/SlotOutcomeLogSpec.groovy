package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * What a finished slot tells the operator: the per-outcome detail line, the canonical summary,
 * and the crash boundary's classification. Extracted from {@code TakeSlotRunner} together with
 * {@link SlotOutcomeLog} itself, which is what lets these decisions be asserted in this module
 * rather than only through :bootstrap's integration suite.
 *
 * FR3, FR9 of harden-logging-observability.
 */
class SlotOutcomeLogSpec extends Specification {

    static final def LOGGER = LoggerFactory.getLogger(SlotOutcomeLogSpec)

    def outcomeLog = new SlotOutcomeLog(LOGGER)
    def ref = new TaskRef('task-7')

    def cleanup() {
        ShutdownPhase.reset()
    }

    // FR3: one outcome, one level-bearing line — every variant that also produces a summary states
    // its detail at DEBUG, so the summary is the only line carrying the level.
    def "the detail line of a summarized outcome is DEBUG and names the variant's own free text"() {
        given:
        def capture = LogCaptureSupport.attach(SlotOutcomeLogSpec, Level.DEBUG)

        when:
        outcomeLog.detail(ref, result)

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.DEBUG
        capture.list[0].formattedMessage.contains('task task-7')
        capture.list[0].formattedMessage.contains(detail)

        cleanup:
        capture.detach()

        where:
        result || detail
        new TakeResult.Delivered(state(), 'shipped it') || 'shipped it'
        new TakeResult.AwaitingHuman(state(), ParkReason.ESCALATION, 'stuck here') || 'stuck here'
        new TakeResult.Aborted(state(), 'clone failed') || 'clone failed'
        new TakeResult.Revoked(state(), 'claim taken') || 'claim taken'
        new TakeResult.EmptyQueue() || 'empty-queue'
    }

    // FR3: Skipped keeps its WARN because no summary is written for it — nothing ran, yet an
    // operator still wants to know the slot declined the task.
    def "a skipped task is the one detail line that stays at WARN"() {
        given:
        def capture = LogCaptureSupport.attach(SlotOutcomeLogSpec)

        when:
        outcomeLog.detail(ref, new TakeResult.Skipped('lost claim race'))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.WARN
        capture.list[0].formattedMessage.startsWith(OperatorEvent.SLOT_SKIPPED.head())
        capture.list[0].formattedMessage.contains('lost claim race')

        cleanup:
        capture.detach()
    }

    // FR3: the canonical summary is emitted for every outcome that ran, at the level the outcome
    // warrants — and for the two that never ran, not at all.
    def "summarize writes one summary line for an outcome that ran"() {
        given:
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        outcomeLog.summarize(new TakeResult.Delivered(state(), 'shipped it'), Duration.ofSeconds(90))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.INFO
        capture.list[0].formattedMessage.contains('task summary: outcome=')

        cleanup:
        capture.detach()
    }

    def "summarize writes nothing for a result that never ran"() {
        given:
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        outcomeLog.summarize(result, Duration.ofSeconds(1))

        then:
        capture.list.isEmpty()

        cleanup:
        capture.detach()

        where:
        result << [
            new TakeResult.EmptyQueue(),
            new TakeResult.Skipped('lost claim race')
        ]
    }

    // FR9: outside the shutdown phase an uncaught crash is an ERROR carrying its full stack.
    def "a crash outside the shutdown phase is an ERROR with its stack"() {
        given:
        def capture = LogCaptureSupport.attach(SlotOutcomeLogSpec)
        def crash = new IllegalStateException('boom')

        when:
        outcomeLog.crashed(ref, crash, Duration.ofSeconds(5))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.ERROR
        capture.list[0].formattedMessage.startsWith(OperatorEvent.SLOT_CRASHED_UNCAUGHT.head())
        capture.list[0].formattedMessage.contains('crashed uncaught')
        capture.list[0].throwableProxy.message == 'boom'

        cleanup:
        capture.detach()
    }

    // FR9: a slot dying because the daemon is stopping is not a fault of the slot's — one WARN
    // naming the exception type, and no stack, since the stack would describe the stop.
    def "a crash during the shutdown phase is a WARN naming the type and carrying no stack"() {
        given:
        def capture = LogCaptureSupport.attach(SlotOutcomeLogSpec)
        ShutdownPhase.begin()

        when:
        outcomeLog.crashed(ref, new InterruptedException('interrupted'), Duration.ofSeconds(5))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.WARN
        capture.list[0].formattedMessage.startsWith(OperatorEvent.SLOT_STOPPED_BY_SHUTDOWN.head())
        capture.list[0].formattedMessage.contains('stopped by the daemon shutdown (InterruptedException)')
        capture.list[0].throwableProxy == null

        cleanup:
        capture.detach()
    }

    // FR3: a task leaving through the crash boundary still gets its one summary — the grep story
    // must not simply stop — and it claims only the outcome and the wall time.
    def "a crash still writes the one summary, claiming only the outcome and the wall time"() {
        given:
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        outcomeLog.crashed(ref, new IllegalStateException('boom'), Duration.ofSeconds(42))

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.WARN
        def rendered = capture.list[0].formattedMessage
        rendered.contains('outcome=aborted')
        rendered.contains('wall=PT42S')
        and: 'nothing the crash destroyed is claimed: no stage, no attempts, no token totals'
        rendered.contains('stage=pipelineEnd')
        rendered.contains('attempts=0')
        rendered.contains('tokens=unreported')

        cleanup:
        capture.detach()
    }

    private static TaskState state() {
        new TaskState(new Position.AtStage('build'), 1, [], ExecutorUsage.none())
    }
}
