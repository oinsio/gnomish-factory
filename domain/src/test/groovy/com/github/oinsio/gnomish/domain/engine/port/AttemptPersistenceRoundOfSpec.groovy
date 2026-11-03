package com.github.oinsio.gnomish.domain.engine.port

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import spock.lang.Specification

/**
 * FR14 of define-executor-contract: the round a persist call records is the last attempt of the
 * state it was handed — every round writer appends it, a pass that advances the position included —
 * so a persistence renders its commit subject from that record.
 */
class AttemptPersistenceRoundOfSpec extends Specification {

    def "FR14: the recorded round is the state's last attempt, also after a same-commit advance"() {
        given:
        def first = RoundStates.roundOf(new AttemptKey('T-1', 'build', 0))
        def second = RoundStates.roundOf(new AttemptKey('T-1', 'build', 1))
        def state = TaskState.atStageStart('build').recordUnburnedRound(first, ExecutorVersions.none())
                .recordPassAndAdvance(second, new Position.AtStage('review'), ExecutorVersions.none())

        expect:
        AttemptPersistence.roundOf(state).is(second)
    }

    def "FR14: a state that records no round is no round write"() {
        when:
        AttemptPersistence.roundOf(TaskState.atStageStart('build'))

        then:
        thrown(NoSuchElementException)
    }
}
