package com.github.oinsio.gnomish.domain.engine

import java.time.Instant

/**
 * FR4, FR9, NFR-C1 of harden-task-branch-contract: a passing round and the advancement it
 * implies are ONE transition, so they are one persisted state — and a pickup that reads that
 * state fast-forwards past the green stage instead of paying for it again.
 *
 * <p>Before this, the loop persisted the pass still positioned at the stage it had just passed
 * and the engine advanced only in memory: an instance killed in that window left a tip saying
 * "the stage passed, and the task is still at it", and the next resume re-ran a whole green
 * stage — real executor and judge spend for a result already recorded.
 *
 * <p>Persistence is the fake {@code InMemoryAttemptPersistence}, so what is asserted here is
 * exactly what the git adapter commits: one write, both facts.
 */
class PassAdvanceOneCommitSpec extends PersistenceOrderingSpecBase {

    // FR4: the persisted state of a passing round already names the stage that follows — and
    // still carries the round itself, because that commit is the only place it is ever recorded.
    def "the passing round's persisted state carries the advanced position and the round"() {
        given: 'two AUTO stages, the first passing on its only round'
        def build = stage('build', 5, [builtin('files_exist')])
        def test = stage('test', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.Pass()
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven through both stages'
        new Engine().run(pipeline(build, test), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the first round was persisted once, positioned at the NEXT stage'
        persistence.entries[0].state.position() == new Position.AtStage('test')

        and: 'the passing round is in that same persisted state — one write, both facts'
        persistence.entries[0].state.attempts()*.round() == [0]
        persistence.entries[0].state.attemptsUsed() == 0

        and: 'the last stage passing advances the same way, to the explicit pipeline end'
        persistence.entries[1].state.position() instanceof Position.PipelineEnd
    }

    // FR9, NFR-C1: the pickup after that write. Resuming from the state the passing round
    // persisted runs the FOLLOWING stage only — the green stage is not executed a second time.
    def "a resume from a recorded pass does not re-run the stage that passed"() {
        given: 'the state a passing round of `build` persisted — position already at `test`'
        def build = stage('build', 5, [builtin('files_exist')])
        def test = stage('test', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed()
        def recorded = TaskState.atStageStart('build')
                .recordPassAndAdvance(passedRound(), new Position.AtStage('test'))

        when: 'a fresh instance resumes from exactly that state'
        def outcome = new Engine().run(pipeline(build, test), CONTEXT, recorded, WORKSPACE, ports())

        then: 'exactly one round ran, and it was the following stage — not the green one'
        executor.requests.size() == 1
        executor.requests[0].stage().name() == 'test'

        and: 'the run reached the pipeline end'
        outcome instanceof TaskOutcome.Completed
    }

    private static AttemptRecord passedRound() {
        new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [],
        ExecutorUsage.none(), JudgeUsage.none(), [])
    }
}
