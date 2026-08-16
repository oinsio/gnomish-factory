package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import java.time.Instant
import spock.lang.Specification

/**
 * {@link TaskOutcomeLine}: the ledger {@code taskOutcome} line's own validation (FR11) —
 * {@code taskId} must not be blank, {@code parkReason} must be set iff {@code outcome} is
 * {@link TaskOutcome#AWAITING_HUMAN}, and {@code attemptsUsed}/{@code wallMillis} must not be
 * negative. Assembly from a {@link com.github.oinsio.gnomish.app.take.TakeResult} is covered
 * separately by {@code TaskOutcomeLineAssemblerSpec}; this spec exercises the record's own
 * compact-constructor guards directly.
 *
 * <p>Implements FR11 of add-serve-observability.
 */
class TaskOutcomeLineSpec extends Specification {

    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
    private static final Instant STARTED = Instant.parse('2026-08-03T10:00:00Z')
    private static final Instant FINISHED = Instant.parse('2026-08-03T10:05:00Z')

    private static TaskOutcomeLine line(String taskId, TaskOutcome outcome, ParkReason parkReason,
            int attemptsUsed, long wallMillis) {
        new TaskOutcomeLine(INSTANCE, taskId, outcome, parkReason, 'build', attemptsUsed, STARTED, FINISHED,
                wallMillis, [:])
    }

    def "rejects a blank taskId"() {
        when:
        line(taskId, TaskOutcome.DELIVERED, null, 1, 1000L)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskOutcomeLine.taskId')

        where:
        taskId << ['', '   ']
    }

    def "rejects a parkReason when outcome is not AWAITING_HUMAN"() {
        when:
        line('PROJ-1', TaskOutcome.DELIVERED, ParkReason.ESCALATION, 1, 1000L)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskOutcomeLine.parkReason')
    }

    def "rejects a missing parkReason when outcome is AWAITING_HUMAN"() {
        when:
        line('PROJ-1', TaskOutcome.AWAITING_HUMAN, null, 1, 1000L)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskOutcomeLine.parkReason')
    }

    def "accepts a parkReason exactly when outcome is AWAITING_HUMAN"() {
        when:
        def result = line('PROJ-1', TaskOutcome.AWAITING_HUMAN, ParkReason.CHECKPOINT, 1, 1000L)

        then:
        result.parkReason() == ParkReason.CHECKPOINT
    }

    def "rejects a negative attemptsUsed"() {
        when:
        line('PROJ-1', TaskOutcome.DELIVERED, null, -1, 1000L)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskOutcomeLine.attemptsUsed')
    }

    def "rejects a negative wallMillis"() {
        when:
        line('PROJ-1', TaskOutcome.DELIVERED, null, 1, -1L)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskOutcomeLine.wallMillis')
    }
}
