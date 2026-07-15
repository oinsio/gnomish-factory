package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification
/**
 * PipelineValidator: the pure aggregator (design D6) that runs every pure rule
 * over a PipelineDefinition and concatenates their located ConfigErrors into
 * one list, in a documented deterministic order — config.yaml level
 * (SchemaVersionRule), then pipeline.yaml level (StageOrderRule), then the
 * pipeline-wide artifact DAG (ArtifactGraphRule), then per-stage local sanity
 * (StageSanityRule). One aggregation pass surfaces every problem at once so the
 * author fixes everything in a single pass (UX1); the same model always yields
 * the same list (NFR-R1). This spec proves the wiring — that each rule
 * contributes and in what order — not each rule's internals (those have their
 * own specs).
 * Implements FR8 of load-pipeline-config.
 */
class PipelineValidatorSpec extends Specification {

    private static StageDefinition.Executor executor(String model = 'claude-sonnet-4-5') {
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, model, [:])
    }

    private static StageDefinition stage(
            String name,
            List<ArtifactInput> inputs,
            List<ArtifactOutput> outputs,
            StageDefinition.Executor executor = executor(),
            List<VerifyCheck> verify = [
                new VerifyCheck.Command('./gradlew check')
            ],
            AutonomyLimits limits = new AutonomyLimits(3)) {
        new StageDefinition(
                name, "Purpose of $name",
                inputs, outputs,
                executor,
                "stages/$name/instructions.md",
                verify,
                limits, AdvancementMode.AUTO)
    }

    /** A fully valid two-stage model: supported version, unique order, a
     * resolved internal reference, sane mechanism and checks. */
    private static PipelineDefinition validModel() {
        new PipelineDefinition(
                SchemaVersionRule.SUPPORTED_VERSION,
                new AutonomyLimits(3),
                [
                    stage('plan', [new ArtifactInput.Source()], [
                        new ArtifactOutput('plan-out')
                    ]),
                    stage('build', [
                        new ArtifactInput.Internal('plan-out')
                    ], [
                        new ArtifactOutput('build-out')
                    ]),
                ])
    }

    // FR8 / delta-spec "Valid configuration loads": a model that violates no
    // rule aggregates to an empty list.
    def "a fully valid model produces no errors"() {
        expect: 'the aggregate error list is empty'
        PipelineValidator.validate(validModel()) == []
    }

    // FR8: each delegated rule's contribution is observable in the aggregate —
    // a representative violation per rule, proving the wiring (PIT mutates the
    // concatenation, so each rule must be reachable).
    def "the aggregate contains the errors of the #ruleName rule"() {
        expect: 'the rule-specific error appears in the aggregate'
        PipelineValidator.validate(model).containsAll(expected)

        where:
        ruleName          | model                        || expected
        'SchemaVersion'   | unsupportedVersionModel()    || SchemaVersionRule.validate(unsupportedVersionModel().schemaVersion())
        'StageOrder'      | emptyOrderModel()            || StageOrderRule.validate(emptyOrderModel().stages())
        'ArtifactGraph'   | danglingRefModel()           || ArtifactGraphRule.validate(danglingRefModel().stages())
        'StageSanity'     | blankModelModel()            || StageSanityRule.validate(blankModelModel().stages())
    }

    // FR8 / UX1 / delta-spec "All problems reported in one pass": a model
    // violating every rule at once yields ALL errors, concatenated in the
    // documented order — SchemaVersion, StageOrder, ArtifactGraph, StageSanity.
    def "a model violating all rules yields every error in the documented order"() {
        given: 'a model that breaks all four rules simultaneously'
        PipelineDefinition model = allRulesBrokenModel()

        expect: 'the aggregate is exactly the four rules concatenated in order'
        PipelineValidator.validate(model) ==
                SchemaVersionRule.validate(model.schemaVersion()) +
                StageOrderRule.validate(model.stages()) +
                ArtifactGraphRule.validate(model.stages()) +
                StageSanityRule.validate(model.stages())

        and: 'and each rule genuinely contributed at least one error'
        !SchemaVersionRule.validate(model.schemaVersion()).isEmpty()
        !StageOrderRule.validate(model.stages()).isEmpty()
        !ArtifactGraphRule.validate(model.stages()).isEmpty()
        !StageSanityRule.validate(model.stages()).isEmpty()
    }

    // NFR-R1: aggregation is deterministic — the same model yields an equal
    // list on every call.
    def "validation is deterministic for #ruleName"() {
        expect: 'two validations of the same model are equal'
        PipelineValidator.validate(model) == PipelineValidator.validate(model)

        where:
        ruleName  | model
        'valid'   | validModel()
        'broken'  | allRulesBrokenModel()
    }

    // NFR-R1 / conventions: the returned list is immutable (defensive copy).
    def "the returned error list is immutable"() {
        when: 'mutating the returned list'
        PipelineValidator.validate(allRulesBrokenModel()).add(
                new ConfigError('config.yaml', 'x', 'y'))

        then: 'it is rejected'
        thrown(UnsupportedOperationException)
    }

    // --- fixtures, one broken rule each -------------------------------------

    private static PipelineDefinition unsupportedVersionModel() {
        new PipelineDefinition(
                '999', new AutonomyLimits(3),
                [
                    stage('plan', [new ArtifactInput.Source()], [
                        new ArtifactOutput('plan-out')
                    ])
                ])
    }

    private static PipelineDefinition emptyOrderModel() {
        new PipelineDefinition(SchemaVersionRule.SUPPORTED_VERSION, new AutonomyLimits(3), [])
    }

    private static PipelineDefinition danglingRefModel() {
        new PipelineDefinition(
                SchemaVersionRule.SUPPORTED_VERSION, new AutonomyLimits(3),
                [
                    stage('plan', [
                        new ArtifactInput.Internal('missing-out')
                    ], [
                        new ArtifactOutput('plan-out')
                    ])
                ])
    }

    private static PipelineDefinition blankModelModel() {
        new PipelineDefinition(
                SchemaVersionRule.SUPPORTED_VERSION, new AutonomyLimits(3),
                [
                    stage('plan', [new ArtifactInput.Source()], [
                        new ArtifactOutput('plan-out')
                    ], executor(''))
                ])
    }

    /** Breaks every rule at once: unsupported version (SchemaVersion), a
     * duplicated stage name (StageOrder), a dangling internal reference
     * (ArtifactGraph), and a blank executor model (StageSanity). */
    private static PipelineDefinition allRulesBrokenModel() {
        new PipelineDefinition(
                '999', new AutonomyLimits(3),
                [
                    stage('plan', [
                        new ArtifactInput.Internal('missing-out')
                    ], [
                        new ArtifactOutput('plan-out')
                    ], executor('')),
                    stage('plan', [new ArtifactInput.Source()], [
                        new ArtifactOutput('build-out')
                    ], executor('')),
                ])
    }
}
