package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * PipelineDefinition: the immutable root of the typed .gnomish/ model — the
 * tree-wide schemaVersion from config.yaml, the pipeline-wide autonomy
 * defaults, and the stages in exactly the pipeline.yaml declaration order
 * (D4). The record is inert data: schema-version support (task 4.1) and
 * non-empty/unique stage order (task 4.2) are located pure-validator
 * concerns, never a constructor exception.
 * Implements FR1 of load-pipeline-config.
 */
class PipelineDefinitionSpec extends Specification {

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

    // FR1: the pipeline root exposes its three components exactly
    def "a pipeline exposes schema version, autonomy defaults and stages as typed components"() {
        given: 'the component values of one pipeline'
        def stages = [
            stage('plan'),
            stage('implement')
        ]

        when: 'a pipeline is modeled from them'
        def pipeline = new PipelineDefinition('1', DEFAULT_LIMITS, stages)

        then: 'each component is exposed exactly'
        pipeline.schemaVersion() == '1'
        pipeline.defaultLimits() == DEFAULT_LIMITS
        pipeline.stages() == stages
    }

    // FR1/FR3 delta-spec scenario: stages appear in exactly the declared
    // (pipeline.yaml) order — list equality is order-sensitive
    def "stages are presented in exactly the declared order"() {
        given: 'three stages declared in a specific order'
        def declared = [
            stage('plan'),
            stage('implement'),
            stage('review')
        ]

        expect: 'the pipeline preserves that order, not any derived one'
        pipelineWith(declared).stages() == declared
        pipelineWith(declared.reverse()).stages() == declared.reverse()
    }

    // FR1: the model is immutable — defensive copy isolates from the source list
    def "pipeline is isolated from later mutation of the source stage list"() {
        given: 'a mutable source list with one stage'
        def source = [stage('plan')]

        when: 'the pipeline is created and the source list grows afterwards'
        def pipeline = pipelineWith(source)
        source << stage('later-noise')

        then: 'the pipeline still holds only the original stage'
        pipeline.stages() == [stage('plan')]
    }

    // FR1: the exposed stage list itself cannot be mutated
    def "the exposed stage list is immutable"() {
        given: 'a modeled pipeline'
        def pipeline = pipelineWith([stage('plan')])

        when: 'a caller tries to append to the exposed list'
        pipeline.stages() << stage('intruder')

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR3/D6: an empty or duplicate stage list is task 4.2's located
    // ConfigError — the record must carry it so the validator can still see
    // and report it, never destroy it with a constructor exception
    def "pipeline carries a semantically invalid stage list (#flaw) without throwing"() {
        when: 'a pipeline is modeled with a stage list that violates FR3'
        def pipeline = pipelineWith(stages)

        then: 'the record carries the list untouched for the validator to report'
        notThrown(Exception)
        pipeline.stages() == stages

        where:
        flaw                    | stages
        'empty'                 | []
        'duplicate stage names' | [stage('plan'), stage('plan')]
    }

    // FR9/D6: schema-version support is task 4.1's located ConfigError — the
    // record carries even a blank version for the validator to report
    def "pipeline carries a blank schema version ('#version') without throwing"() {
        when: 'a pipeline is modeled with a schema version that violates FR9'
        def pipeline = new PipelineDefinition(version, DEFAULT_LIMITS, [stage('plan')])

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        pipeline.schemaVersion() == version

        where:
        version << ['', '   ']
    }

    // FR1: pipelines are plain values — LoadOutcome and tests compare by content
    def "pipelines with the same components are equal values"() {
        expect: 'two independently constructed pipelines with equal fields are equal'
        pipelineWith([stage('plan')]) == pipelineWith([stage('plan')])
    }
}
