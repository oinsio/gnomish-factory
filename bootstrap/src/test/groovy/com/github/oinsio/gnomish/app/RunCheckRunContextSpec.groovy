package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.StatusSnapshotHolder
import spock.lang.Specification

/**
 * NFR-S2, D5 of add-plugin-architecture: the run's answer to the engine-defined interpolation
 * whitelist. The task id is the tracker's own, the branch is derived by the same sanitizer every
 * other component derives it with, and the stage name is read live — one client serves a whole run,
 * so a captured name would address the stage the run started at.
 */
class RunCheckRunContextSpec extends Specification {

    private static final TaskContext TASK = new TaskContext('PROJ-42', 'title', 'body', [])

    private static StatusSnapshotHolder holderAt(String stage) {
        new StatusSnapshotHolder(TaskState.atStageStart(stage), 3)
    }

    def "supplies the task id and the branch derived from it"() {
        given:
        def context = RunCheckRunContext.of(TASK, holderAt('implement'))

        expect:
        context.value(CheckRunContext.TASK_ID).get() == 'PROJ-42'
        context.value(CheckRunContext.TASK_BRANCH).get() == 'gnomish/PROJ-42'
    }

    def "reads the stage name live, as the position moves"() {
        given:
        def holder = holderAt('implement')
        def context = RunCheckRunContext.of(TASK, holder)

        expect:
        context.value(CheckRunContext.STAGE_NAME).get() == 'implement'

        when:
        holder.updateState(TaskState.atStageStart('review'))

        then:
        context.value(CheckRunContext.STAGE_NAME).get() == 'review'
    }

    // NFR-S2: at the pipeline's end nothing is under verification, so the lookup is empty and a check
    //     interpolating the stage name fails closed rather than addressing a placeholder.
    def "supplies no stage name past the end of the pipeline"() {
        given:
        def holder = holderAt('implement')
        holder.updateState(new TaskState(new Position.PipelineEnd(), 0, [], TaskState.atStageStart('x').totals()))

        expect:
        RunCheckRunContext.of(TASK, holder).value(CheckRunContext.STAGE_NAME).isEmpty()
    }

    // The whitelist is closed: a name outside it is not a lookup this context can answer.
    def "supplies nothing for a name outside the whitelist"() {
        expect:
        RunCheckRunContext.of(TASK, holderAt('implement')).value('env.SONAR_TOKEN').isEmpty()
    }
}
