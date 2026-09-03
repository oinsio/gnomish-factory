package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR15 of harden-logging-observability: {@link GuardedPark}'s three degrade edges are contract, not
 * commentary — the park is the transition that hands a task to a human, so an operator who cannot
 * see why one was skipped, unverified or deferred has lost the task.
 *
 * <p>Each assertion pins the emitter's {@link OperatorEvent} code, its level, and the {@code taskId}
 * attribution the line must carry. The wording is deliberately not asserted: the code is the
 * contract (ADR 0004), so the sentence may be rewritten without touching this spec.
 *
 * <p>{@code GuardedPark.attempt} logs through the <em>caller's</em> logger, so the capture is
 * attached to this spec's own class — the logger the specs below hand it.
 */
class GuardedParkLogSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-9')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    Tracker tracker = Mock(Tracker)

    private static TrackerTask task(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot('PROJ-9', 'title', 'body'), state, AbortFacts.none(), false)
    }

    private String recoveredPark(Runnable receipt) {
        GuardedPark.attempt(
                tracker,
                REF,
                INSTANCE,
                ParkReason.ESCALATION,
                { note -> 'parked for a human' },
                VirtualTimeRetries.terminalWrite(),
                new ParkTransition.Recovered(new ParkDeliveryVerdict.Delivered(), receipt),
                LoggerFactory.getLogger(GuardedParkLogSpec),
                'park')
    }

    def "a probe the tracker cannot answer leaves a coded WARN naming the task, and the park is re-driven"() {
        given: 'the probe throws, and the claim check afterwards reports the claim still ours'
        def receipts = 0
        def probed = false
        tracker.fetchTask(REF) >> {
            if (!probed) {
                probed = true
                throw new RuntimeException('tracker unreachable')
            }
            task(new TrackerTaskState.Working(INSTANCE.value()))
        }
        def logs = LogCaptureSupport.attach(GuardedParkLogSpec)

        when:
        recoveredPark({ receipts++ })

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, 'parked for a human')
        receipts == 1

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.PARK_LANDING_UNVERIFIED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('PROJ-9')

        cleanup:
        logs.detach()
    }

    def "a claim held by another instance leaves a coded WARN and skips both the park and its receipt"() {
        given:
        def receipts = 0
        tracker.fetchTask(REF) >> task(new TrackerTaskState.Working('someone-else'))
        def logs = LogCaptureSupport.attach(GuardedParkLogSpec)

        when:
        recoveredPark({ receipts++ })

        then:
        0 * tracker.park(*_)
        receipts == 0

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.PARK_SKIPPED_CLAIM_LOST.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('PROJ-9')

        cleanup:
        logs.detach()
    }

    def "a park the retry bound never confirms leaves a coded ERROR and no receipt"() {
        given:
        def receipts = 0
        tracker.fetchTask(REF) >> task(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.park(REF, ParkReason.ESCALATION, 'parked for a human') >> {
            throw new TrackerUnavailableException('tracker down')
        }
        def logs = LogCaptureSupport.attach(GuardedParkLogSpec)

        when:
        recoveredPark({ receipts++ })

        then: 'the marker stays set, so a later resume re-drives the deferred park'
        receipts == 0

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.PARK_UNWRITTEN_AFTER_RETRIES.head())
        }
        event != null
        event.level == Level.ERROR
        event.formattedMessage.contains('PROJ-9')

        cleanup:
        logs.detach()
    }
}
