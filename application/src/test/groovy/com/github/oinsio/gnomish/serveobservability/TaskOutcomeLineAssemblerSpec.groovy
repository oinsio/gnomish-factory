package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.Snapshots
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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

    private static TaskState stateAt(Position position, int attemptsUsed, UsageSnapshot totals) {
        return new TaskState(position, attemptsUsed, [], totals, ExecutorVersions.none())
    }

    def "maps Delivered to a delivered line with no parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('review'), 2, UsageSnapshot.EMPTY)
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
        def finalState = stateAt(new Position.AtStage('build'), 1, UsageSnapshot.EMPTY)
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
        def finalState = stateAt(new Position.AtStage('build'), 0, UsageSnapshot.EMPTY)
        def result = new TakeResult.Aborted(finalState, UntrustedText.subprocess('durability guarantee broke'))

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.outcome() == TaskOutcome.ABORTED
        line.parkReason() == null
    }

    def "maps Revoked to a revoked line with no parkReason"() {
        given:
        def finalState = stateAt(new Position.AtStage('build'), 0, UsageSnapshot.EMPTY)
        def result = new TakeResult.Revoked(finalState, UntrustedText.tracker('claim lost mid-run'))

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.outcome() == TaskOutcome.REVOKED
        line.parkReason() == null
    }

    def "maps a PipelineEnd position to a null stage"() {
        given:
        def finalState = stateAt(new Position.PipelineEnd(), 0, UsageSnapshot.EMPTY)
        def result = new TakeResult.Delivered(finalState, 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.stage() == null
    }

    def "carries the finalState's cumulative tokensByModel across, converted to LedgerTokenUsage"() {
        given:
        def tokens = ['claude-x': new TokenUsage(100L, 50L, 10L, 5L)]
        def finalState = stateAt(new Position.AtStage('build'), 0, Snapshots.executed(tokens))
        def result = new TakeResult.Delivered(finalState, 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.tokensByModel() == ['claude-x': new LedgerTokenUsage(100L, 50L, 10L, 5L)]
    }

    // FR17 of define-executor-contract: the line takes the snapshot's per-model map from the one
    //     usage fold, the judge votes' tokens included, never a second sum
    def "carries the snapshot's tokens per model summed over the roles, judge votes included"() {
        given:
        def tokens = new TokenUsage(1L, 2L, 3L, 4L)
        def snapshot = new UsageSnapshot([(ExecutorRole.TRANSFORM): ['m': tokens], (ExecutorRole.VERDICT): ['m': tokens, 'j': tokens]],
        new UsageCost(BigDecimal.ZERO, [:], false), 1, null)
        def result = new TakeResult.Delivered(stateAt(new Position.AtStage('build'), 0, snapshot), 'shipped it')

        when:
        def line = TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, result, STARTED_AT, FINISHED_AT)

        then:
        line.tokensByModel() == ['m': new LedgerTokenUsage(2L, 4L, 6L, 8L), 'j': new LedgerTokenUsage(1L, 2L, 3L, 4L)]
    }

    def "maps EmptyQueue and Skipped to no line at all"() {
        expect:
        TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, new TakeResult.EmptyQueue(), STARTED_AT, FINISHED_AT) == null
        TaskOutcomeLineAssembler.assemble(INSTANCE, TASK_ID, new TakeResult.Skipped(UntrustedText.tracker('lost claim race')), STARTED_AT, FINISHED_AT) == null
    }
}
