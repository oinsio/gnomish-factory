package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import java.time.Instant
import spock.lang.Specification

/**
 * {@link TaskOutcomeLineAssembler}: the pure mapping from a terminal {@link TakeResult} to a
 * {@link TaskOutcomeLine} (design D6, FR11). Only the four variants carrying a {@code
 * finalState} — {@code Delivered}/{@code AwaitingHuman}/{@code Aborted}/{@code Revoked} — map to
 * a line; {@code EmptyQueue}/{@code Skipped} map to {@code null} since no engine run happened.
 *
 * <p>Implements FR11 of add-serve-observability.
 */
class TaskOutcomeLineAssemblerSpec extends Specification {

    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-x7k2q1', 'worker-1', '0.1.0')
    private static final String TASK_ID = 'PROJ-1'
    private static final Instant STARTED_AT = Instant.parse('2026-08-03T10:00:00Z')
    private static final Instant FINISHED_AT = Instant.parse('2026-08-03T10:05:30Z')

    private static TaskState stateAt(Position position, int attemptsUsed, ExecutorUsage totals) {
        return new TaskState(position, attemptsUsed, [], totals)
    }

    def "maps Delivered to a delivered line with no parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('review'), 2, ExecutorUsage.none())
        def result = new TakeResult.Delivered(finalState, 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line == new TaskOutcomeLine(
                INSTANCE, TASK_ID, TaskOutcome.DELIVERED, null, 'review', 2,
                STARTED_AT, FINISHED_AT, 330_000L, [:])
    }

    def "maps AwaitingHuman to an awaitingHuman line carrying its parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('build'), 1, ExecutorUsage.none())
        def result = new TakeResult.AwaitingHuman(finalState, ParkReason.ESCALATION, 'needs a human')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.outcome() == TaskOutcome.AWAITING_HUMAN
        line.parkReason() == ParkReason.ESCALATION
        line.stage() == 'build'
    }

    def "maps Aborted to an aborted line with no parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('build'), 0, ExecutorUsage.none())
        def result = new TakeResult.Aborted(finalState, 'durability guarantee broke')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.outcome() == TaskOutcome.ABORTED
        line.parkReason() == null
    }

    def "maps Revoked to a revoked line with no parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('build'), 0, ExecutorUsage.none())
        def result = new TakeResult.Revoked(finalState, 'claim lost mid-run')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.outcome() == TaskOutcome.REVOKED
        line.parkReason() == null
    }

    def "maps a PipelineEnd position to a null stage"() {
        given:
        def finalState = stateAt(new Position.PipelineEnd(), 0, ExecutorUsage.none())
        def result = new TakeResult.Delivered(finalState, 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.stage() == null
    }

    def "carries the finalState's cumulative tokensByModel across, converted to LedgerTokenUsage"() {
        given:
        def tokens = ['claude-x': new TokenUsage(100L, 50L, 10L, 5L)]
        def finalState = stateAt(new Position.AtStage('build'), 0, new ExecutorUsage(null, [], tokens))
        def result = new TakeResult.Delivered(finalState, 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.tokensByModel() == ['claude-x': new LedgerTokenUsage(100L, 50L, 10L, 5L)]
    }

    def "maps EmptyQueue and Skipped to no line at all"() {
        expect:
        TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, new TakeResult.EmptyQueue(), STARTED_AT, FINISHED_AT) == null
        TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, new TakeResult.Skipped('lost claim race'), STARTED_AT, FINISHED_AT) == null
    }
}
