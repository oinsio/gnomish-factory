package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * StatusSnapshotHolder: the live event fold of one run — a synchronized holder of
 * the current TaskState (design D7). Implements FR10, D7 of add-manual-run; FR6 of
 * make-run-headless.
 */
class StatusSnapshotHolderSpec extends Specification {

    // D7: a fresh holder starts at the given initial state
    def "starts at the given initial state"() {
        given: 'a holder created with an initial state'
        def initial = TaskState.atStageStart('implement')
        def holder = new StatusSnapshotHolder(initial)

        expect: 'the held state is the starting state'
        holder.state() == initial
    }

    // D7: updateState replaces the held TaskState
    def "updateState replaces the held TaskState"() {
        given: 'a holder at the pipeline start'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'))
        def advanced = TaskState.atStageStart('implement').advanceTo(new Position.AtStage('review'))

        when: 'the state is updated'
        holder.updateState(advanced)

        then: 'the held state reflects the update'
        holder.state() == advanced
    }
}
