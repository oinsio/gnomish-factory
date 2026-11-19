package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * FR20 of migrate-verify-to-executor-contract (design D21 item 12, D22; the 4.1r note on task
 * 4.2d): the state a run starts from reads the open round's pending entries off the recorded state
 * <em>before</em> the resume's normalization ({@link TaskState#startOfStage}), which empties the
 * list together with a finished stage's attempts. A pending commit of a stage's first round carries
 * the attempts the snapshot below it registered — the previous stage's passing round — so a resume
 * that read its entries after the normalization would lose them and start the round's checks
 * again. Since task 4.3 the round's pending writes carry the persisted state itself
 * ({@code EnginePendingWriteStateSpec}); the real-medium second-stage kill points are
 * {@code HostPendingPollKillPointSpec}'s.
 */
class EngineResumedStateSpec extends Specification {

    static TaskState pendingAt(Position position, AttemptRecord.Result last) {
        TaskStatePendingPollsSpec.pendingAt(position, last)
    }

    def "a pending commit of a second stage's first round keeps its entries through the normalization"() {
        given: 'the previous stage\'s pass rides the state, as the snapshot registered it'
        def recorded = pendingAt(new Position.AtStage('test'), AttemptRecord.Result.PASSED)

        when:
        def resumed = Engine.resumedFrom(recorded)

        then: 'the finished stage is normalized away: the open round is round zero of its stage'
        resumed.attempts().isEmpty()
        resumed.attemptsUsed() == 0
        resumed.position() == new Position.AtStage('test')

        and: 'the entries the round wrote reach its verification'
        resumed.pendingPolls() == TaskStatePendingPollsSpec.POLLS

        and: 'the normalization alone drops them, which is what reading them first prevents'
        recorded.startOfStage().pendingPolls().isEmpty()
    }

    def "a state with nothing to normalize is resumed as recorded, its entries included"() {
        given:
        def recorded = pendingAt(new Position.AtStage('build'), AttemptRecord.Result.QUALITY_FAILURE)

        expect:
        Engine.resumedFrom(recorded).is(recorded)
    }

    def "a live state carries no entry and is unchanged"() {
        given:
        def live = TaskState.atStageStart('build')

        expect:
        Engine.resumedFrom(live).is(live)
        Engine.resumedFrom(live).pendingPolls().isEmpty()
    }
}
