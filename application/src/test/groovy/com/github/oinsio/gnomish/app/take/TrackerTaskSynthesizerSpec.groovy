package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * TrackerTaskSynthesizer: converts a first-claim TaskSnapshot into the engine's
 * initial TaskContext/TaskState — the tracker-task analogue of
 * AdHocTaskSynthesizer. Covers verbatim id/title/body flow-through, an empty
 * initial decision list, and positioning at the pipeline's first declared stage.
 *
 * Implements FR11 of add-tracker-port.
 */
class TrackerTaskSynthesizerSpec extends Specification {

    private static final AutonomyLimits DEFAULT_LIMITS = new AutonomyLimits(3)

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, "The ${name} section of the pipeline" as String,
                [new ArtifactInput.Source()], [
                    new ArtifactOutput("${name}-out" as String)
                ],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
                "stages/${name}/instructions.md" as String,
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipelineWith(List<StageDefinition> stages) {
        new PipelineDefinition('1', DEFAULT_LIMITS, stages)
    }

    // FR11: id/title/body flow from the snapshot into TaskContext verbatim
    def "the snapshot's id, title and body flow verbatim into TaskContext"() {
        given: 'a task snapshot frozen at first claim'
        def snapshot = new TaskSnapshot('PROJ-42', 'Fix the widget', 'The widget is broken.')
        def definition = pipelineWith([
            stage('plan'),
            stage('implement')
        ])

        when: 'the tracker task is synthesized'
        def synthesized = TrackerTaskSynthesizer.synthesize(snapshot, definition)

        then: 'the resulting context carries the snapshot fields unchanged'
        synthesized.context() == new TaskContext('PROJ-42', 'Fix the widget', 'The widget is broken.', [])
    }

    // FR11: a freshly claimed task has collected no human decisions yet
    def "the initial context starts with an empty decisions list"() {
        given:
        def snapshot = new TaskSnapshot('PROJ-1', 'Title', 'Body')
        def definition = pipelineWith([stage('plan')])

        when:
        def synthesized = TrackerTaskSynthesizer.synthesize(snapshot, definition)

        then:
        synthesized.context().decisions() == []
    }

    // FR11, D4: a tracker task always starts at the pipeline's first declared
    // stage — no --from-stage support for take
    def "the initial state positions at the pipeline's first declared stage"() {
        given: 'a pipeline whose first declared stage is "plan"'
        def snapshot = new TaskSnapshot('PROJ-7', 'Title', 'Body')
        def definition = pipelineWith([
            stage('plan'),
            stage('implement'),
            stage('review')
        ])

        when:
        def synthesized = TrackerTaskSynthesizer.synthesize(snapshot, definition)

        then: 'the initial state is at the first stage with no attempts burned'
        synthesized.initialState() == TaskState.atStageStart('plan')
    }

    // FR11, D4: declaration order determines the start stage, not any other
    // ordering — reversing the declared stages changes which one is first
    def "the start stage follows declaration order, not stage name"() {
        given: 'the same stages declared in reverse order'
        def snapshot = new TaskSnapshot('PROJ-9', 'Title', 'Body')
        def definition = pipelineWith([
            stage('review'),
            stage('implement'),
            stage('plan')
        ])

        when:
        def synthesized = TrackerTaskSynthesizer.synthesize(snapshot, definition)

        then:
        synthesized.initialState() == TaskState.atStageStart('review')
    }

    // FR11: an empty snapshot body is a legal, verbatim case — TaskContext
    // permits an empty body (many tracker issues have no description)
    def "an empty snapshot body flows through unchanged"() {
        given:
        def snapshot = new TaskSnapshot('PROJ-3', 'Title only', '')
        def definition = pipelineWith([stage('plan')])

        when:
        def synthesized = TrackerTaskSynthesizer.synthesize(snapshot, definition)

        then:
        synthesized.context().body() == ''
    }
}
