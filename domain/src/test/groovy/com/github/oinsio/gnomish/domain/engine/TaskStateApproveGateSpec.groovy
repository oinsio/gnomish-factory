package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@code TaskState.approveGate(definition)}: the state the approval of a gate writes — the
 * position following the gate's stage in the pinned pipeline, computed through {@code
 * Advancement.afterGate}, with the attempt history, the burn count and the totals untouched (a
 * checkpoint resets nothing). Refuses a state that is not at a gate, and a gate whose stage the
 * pipeline does not declare.
 *
 * <p>Implements FR3 of make-checkpoint-gate-durable (design D2, D7).
 */
class TaskStateApproveGateSpec extends Specification {

    static final def DEFINITION = AdvancementSpec.pipeline(
    AdvancementSpec.stage('build', AdvancementMode.MANUAL, []),
    AdvancementSpec.stage('test', AdvancementMode.MANUAL, []))

    static final def TOTALS = new ExecutorUsage(Duration.ofSeconds(7), [], [:])

    static TaskState gateAt(String stage) {
        def passed = new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(), JudgeUsage.none(), [])
        new TaskState(new Position.AwaitingApproval(stage), 2, [passed], TOTALS)
    }

    // FR3: the approved position is the one following the gate's stage — the next stage in the
    //      middle, the pipeline end after the last — never the gate itself, whatever its mode
    def "approveGate moves the position past the gate and leaves everything else untouched"() {
        given: 'a state held at the gate of a stage'
        def gate = gateAt(stage)

        when: 'the gate is approved against the pinned pipeline'
        def approved = gate.approveGate(DEFINITION)

        then: 'the position is the one following the stage'
        approved.position() == expected

        and: 'the attempt history, the burn count and the totals are untouched'
        approved.attempts() == gate.attempts()
        approved.attemptsUsed() == 2
        approved.totals() == TOTALS

        where:
        stage | expected
        'build' | new Position.AtStage('test')
        'test' | new Position.PipelineEnd()
    }

    // FR3: only a gate can be approved — any other position is refused, no state produced
    def "approveGate refuses a state that is not at a gate"() {
        when: 'a state at a stage or at the pipeline end is approved'
        new TaskState(position, 0, [], ExecutorUsage.none()).approveGate(DEFINITION)

        then: 'the approval is refused'
        def failure = thrown(IllegalStateException)
        failure.message.contains('not a gate')

        where:
        position << [
            new Position.AtStage('build'),
            new Position.PipelineEnd()
        ]
    }

    // FR3: a gate whose stage the pinned pipeline does not declare cannot be approved
    def "approveGate refuses a gate whose stage the pipeline does not declare"() {
        when: 'a gate on an undeclared stage is approved'
        gateAt('deploy').approveGate(DEFINITION)

        then: 'the approval is refused'
        def failure = thrown(IllegalStateException)
        failure.message.contains('not declared')
    }

    // FR1, FR12: at a gate the passing round belongs to the stage the position names, so a pickup's
    //      normalization keeps it; once approved, the same normalization drops it as for any advance
    def "startOfStage keeps the gate's passing round and drops it once the gate is approved"() {
        given: 'a state held at the gate of a stage whose passing round is recorded'
        def gate = gateAt('build')

        expect: 'the gate is its own start of stage — nothing dropped'
        gate.startOfStage().is(gate)

        and: 'past the approved gate the passing round is the finished stage history'
        def started = gate.approveGate(DEFINITION).startOfStage()
        started.position() == new Position.AtStage('test')
        started.attempts().isEmpty()
        started.attemptsUsed() == 0
        started.totals() == TOTALS
    }
}
