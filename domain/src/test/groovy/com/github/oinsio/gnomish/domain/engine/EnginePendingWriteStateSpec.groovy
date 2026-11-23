package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Instant

/**
 * FR2, FR20 of migrate-verify-to-executor-contract (design D21 item 12; the open point of task 4.2d
 * settled in 4.3): a pending write carries the last persisted state byte for byte but for its
 * entries — on a stage's first round too, whose persisted state still holds the previous stage's
 * passing round — while the round itself is numbered from the stage's own history ({@link
 * Engine#resumedFrom}). Live, and on a resume over such a pending commit, through the whole engine.
 */
class EnginePendingWriteStateSpec extends PendingPollSpecBase {

    static StageDefinition stage(String name, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [], new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), TRACE, [])
    }

    def listener = new RecordingEventListener()

    TaskOutcome run(ScriptedExecutor executor, PipelineDefinition definition, TaskState state) {
        def ports = new EnginePorts(executor, listener, persistence,
                VirtualTimeEquipment.on(clock, sleeper), {
                    Optional.of(TOKEN)
                } as RoundIdentity)
        new Engine().run(definition, CONTEXT, state, WORKSPACE, ports)
    }

    // Scenario: verification/pending-poll / A pending entry names no outcome
    def "FR20: the pending write of a second stage's first round carries the first stage's persisted pass"() {
        given: 'a stage with no check passes; the next stage\'s check answers pending, then passes'
        def ci = check()
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build', []),
            stage('test', [ci])
        ])
        def executor = new ScriptedExecutor([
            completed(),
            completed(),
            pending('k-1'),
            passed()
        ])

        when:
        def outcome = run(executor, definition, TaskState.atStageStart('build'))

        then: 'the write is the build round\'s commit with the entry added, nothing else changed'
        def passedBuild = persistence.entries[0].state
        passedBuild.attempts()*.result() == [AttemptRecord.Result.PASSED]
        persistence.pending*.state == [
            passedBuild.withPendingPolls([
                entry(ref(0, ci), 'k-1', persistence.pending[0].state.pendingPolls()[0].since())
            ])
        ]

        and: 'the round is round zero of its stage, and its close drops the entry'
        def closed = persistence.entries[1].state
        closed.attempts()*.round() == [0]
        closed.pendingPolls().isEmpty()
        outcome instanceof TaskOutcome.Completed
    }

    def "FR3, FR20: a resume over that pending commit continues its entry and a later write keeps the previous stage's pass"() {
        given: 'the pending commit of the test stage\'s first round, read at the register'
        def first = check('first')
        def second = check('second')
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build', []),
            stage('test', [first, second])
        ])
        def passedBuild = TaskState.atStageStart('build').recordPassAndAdvance(round0Passed(), new Position.AtStage('test'),
                ExecutorVersions.none())
        def recorded = passedBuild.withPendingPolls([
            entry(ref(0, first), 'k-a', clock.instant())
        ])
        def executor = new ScriptedExecutor([
            completed(),
            passed(),
            pending('k-b'),
            passed()
        ])

        when:
        run(executor, definition, recorded)

        then: 'the recorded key is polled, never started; the other check starts'
        turns(executor) == [
            null,
            [RECORDED, 'k-a'],
            null,
            [ISSUED, 'k-b']
        ]

        and: 'the new write carries the persisted state as read, the previous stage\'s pass included'
        def written = persistence.pending[0].state
        written.attempts() == recorded.attempts()
        written.position() == recorded.position()
        written.attemptsUsed() == recorded.attemptsUsed()
        written.pendingPolls()*.key()*.value() == ['k-a', 'k-b']

        and: "the round was round zero of its stage, and the only write"
        persistence.pending.size() == 1
        persistence.entries[0].state.attempts()*.round() == [0]
    }

    private static AttemptRecord round0Passed() {
        AttemptRecords.of(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(), [], Stop.none())
    }
}
