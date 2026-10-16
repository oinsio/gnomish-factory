package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.TrackerTaskFixtures
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR2 of fix-terminal-receipt-convergence: a recovered park is confirmed from the task's tenure
 * boundary — the thread's last boundary marker, whatever claims follow it — never from the current
 * state, which the pickup's own claim has already made {@code Working}. Every task below is
 * therefore {@code Working} held by this instance, exactly as at a real pickup.
 *
 * Both readers are covered: {@link GuardedPark}'s protocol probe and {@link ParkLanding#confirmFromHistory},
 * which a pickup's route asks first.
 *
 * <p>NFR-O1: a park confirmed from history leaves one DEBUG line.
 */
class GuardedParkSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-9')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    Tracker tracker = Mock(Tracker)

    private static TrackerTask heldWith(BoundaryKind boundary) {
        TrackerTaskFixtures.heldAfter(REF, INSTANCE, boundary)
    }

    private void recoveredPark(Runnable receipt) {
        GuardedPark.attempt(
                TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE),
                ParkReason.ESCALATION,
                'parked for a human',
                VirtualTimeRetries.terminalWrite(),
                new ParkTransition.Recovered(new ParkDeliveryVerdict.Delivered(), receipt),
                LoggerFactory.getLogger(GuardedParkSpec),
                'park')
    }

    // Scenario: tracker/tracker-take / A landed park with a lost receipt is not re-driven
    // Scenario: tracker/tracker-take / A lost park is re-driven
    def "FR2: a recovered park over tenure boundary #boundary is #outcome"() {
        given:
        def receipts = 0
        tracker.fetchTask(REF) >> heldWith(boundary)

        when:
        recoveredPark({ receipts++ })

        then: 'the receipt is owed either way: confirmed from history, or after the re-drive'
        parks * tracker.park(REF, ParkReason.ESCALATION, 'parked for a human')
        receipts == 1

        where:
        boundary || parks | outcome
        BoundaryKind.PARK || 0 | 'confirmed, not re-driven'
        BoundaryKind.STALE_CLAIM_REMOVED || 1 | 're-driven'
        null || 1 | 're-driven'
        BoundaryKind.FINISH || 1 | 're-driven, since only PARK is this park'
        BoundaryKind.ABORT || 1 | 're-driven, since only PARK is this park'
    }

    def "FR2: confirmFromHistory over tenure boundary #boundary answers #confirmed"() {
        given:
        def receipts = 0
        tracker.fetchTask(REF) >> heldWith(boundary)

        when:
        def answer = ParkLanding.confirmFromHistory(TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE),
                { receipts++ }, LoggerFactory.getLogger(GuardedParkSpec))

        then: 'only a confirmed park records its receipt, and nothing is ever written to the tracker'
        answer == confirmed
        receipts == (confirmed ? 1 : 0)
        0 * tracker.park(*_)

        where:
        boundary || confirmed
        BoundaryKind.PARK || true
        BoundaryKind.STALE_CLAIM_REMOVED || false
        null || false
        BoundaryKind.FINISH || false
        BoundaryKind.ABORT || false
    }

    def "NFR-O1: a park confirmed from history leaves one DEBUG line naming the task"() {
        given:
        tracker.fetchTask(REF) >> heldWith(BoundaryKind.PARK)
        def logs = LogCaptureSupport.attach(GuardedParkSpec, Level.DEBUG)

        when:
        ParkLanding.confirmFromHistory(TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE), {}, LoggerFactory.getLogger(GuardedParkSpec))

        then:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('PROJ-9')
        logs.list[0].formattedMessage.contains('confirmed')

        cleanup:
        logs.detach()
    }

    def "NFR-O1: a park that is not confirmed leaves no line"() {
        given:
        tracker.fetchTask(REF) >> heldWith(BoundaryKind.STALE_CLAIM_REMOVED)
        def logs = LogCaptureSupport.attach(GuardedParkSpec, Level.DEBUG)

        when:
        ParkLanding.confirmFromHistory(TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE), {}, LoggerFactory.getLogger(GuardedParkSpec))

        then:
        logs.list.isEmpty()

        cleanup:
        logs.detach()
    }

    def "a tracker that cannot be asked confirms nothing and leaves only a DEBUG line, since the re-drive probes again"() {
        given:
        def receipts = 0
        tracker.fetchTask(REF) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def logs = LogCaptureSupport.attach(GuardedParkSpec, Level.DEBUG)

        when:
        def answer = ParkLanding.confirmFromHistory(TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE),
                { receipts++ }, LoggerFactory.getLogger(GuardedParkSpec))

        then:
        !answer
        receipts == 0
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('PROJ-9')
        logs.list[0].throwableProxy != null

        cleanup:
        logs.detach()
    }
}
