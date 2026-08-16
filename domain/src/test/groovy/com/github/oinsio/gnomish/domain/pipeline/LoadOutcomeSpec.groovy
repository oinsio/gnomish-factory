package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * LoadOutcome: sealed result of loading a .gnomish/ tree (design D3) —
 * either Loaded(PipelineDefinition) or Invalid with the complete, non-empty,
 * immutable list of located ConfigErrors. Validation failure is data, never
 * an exception (UX1). Implements FR8 of load-pipeline-config.
 */
class LoadOutcomeSpec extends Specification {

    def someError = new ConfigError('config.yaml', 'schemaVersion', 'missing required field')

    // FR8: the valid branch carries the typed pipeline model
    def "Loaded exposes the pipeline definition"() {
        given: 'a minimal valid pipeline definition'
        def definition = aPipeline()

        when: 'the loaded outcome wraps it'
        LoadOutcome outcome = new LoadOutcome.Loaded(definition)

        then: 'the outcome exposes exactly that definition'
        outcome.definition() == definition
    }

    // Minimal one-stage PipelineDefinition fixture (FR1 shape asserted in
    // PipelineDefinitionSpec — here it is only the Loaded payload)
    private static PipelineDefinition aPipeline() {
        def stage = new StageDefinition(
                'implement', 'Turn the plan into a verified implementation',
                [new ArtifactInput.Source()], [
                    new ArtifactOutput('impl-diff')
                ],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:]),
                'stages/implement/instructions.md',
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    // FR8: the invalid branch aggregates all problems, order preserved (UX1)
    def "Invalid exposes all aggregated errors in order"() {
        given: 'two independent located problems'
        def other = new ConfigError('pipeline.yaml', 'stages', 'empty stage list')

        when: 'the invalid outcome aggregates both'
        LoadOutcome outcome = new LoadOutcome.Invalid([someError, other])

        then: 'the outcome carries both errors in aggregation order'
        outcome.errors() == [someError, other]
    }

    // FR8: the error list is non-empty by contract — an Invalid with nothing
    // to fix would be contradictory
    def "Invalid rejects an empty error list"() {
        when: 'an invalid outcome is created without any error'
        new LoadOutcome.Invalid([])

        then: 'construction fails and the message states the non-empty contract'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('at least one ConfigError')
    }

    // FR8: outcomes are immutable — defensive copy isolates from the source list
    def "Invalid is isolated from later mutation of the source list"() {
        given: 'a mutable source list with one error'
        def source = [someError]

        when: 'the outcome is created and the source list grows afterwards'
        def outcome = new LoadOutcome.Invalid(source)
        source << new ConfigError('pipeline.yaml', 'stages', 'later noise')

        then: 'the outcome still holds only the original error'
        outcome.errors() == [someError]
    }

    // FR8: the exposed error list itself cannot be mutated
    def "Invalid's error list is immutable"() {
        given: 'an invalid outcome'
        def outcome = new LoadOutcome.Invalid([someError])

        when: 'a caller tries to append to the exposed list'
        outcome.errors() << new ConfigError('pipeline.yaml', 'stages', 'intruder')

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // D3: the outcome is sealed over exactly the two variants, so future
    // consumers can switch exhaustively
    def "LoadOutcome is sealed over exactly Loaded and Invalid"() {
        expect: 'the interface is sealed'
        LoadOutcome.isSealed()

        and: 'its only permitted implementations are Loaded and Invalid'
        LoadOutcome.permittedSubclasses*.name.toSorted() ==
                [
                    LoadOutcome.Invalid.name,
                    LoadOutcome.Loaded.name
                ].toSorted()
    }
}
