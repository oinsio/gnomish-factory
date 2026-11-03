package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ExecutorReferenceRule (FR9, FR10 of define-executor-contract; design D5 phase a): a stage's
 * {@code executor.name} names a declaration of the {@code executors:} section, and a {@code program}
 * stage names one. Pure: no process, no describe.
 */
class ExecutorReferenceRuleSpec extends Specification {

    private static StageDefinition stage(String name, ExecutorType type, String executorName) {
        new StageDefinition(name, "Purpose of $name", [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(type, '', [:], Sandbox.none(), executorName),
        "stages/$name/instructions.md", [], new AutonomyLimits(2), AdvancementMode.AUTO)
    }

    private static ExecutorDeclaration declared(String name) {
        new ExecutorDeclaration(name, ExecutorDeclaration.Type.PROGRAM, [name], [:], [], [])
    }

    private static PipelineDefinition pipeline(List<StageDefinition> stages, List<ExecutorDeclaration> executors) {
        new PipelineDefinition('1', new AutonomyLimits(2), stages, null, executors)
    }

    // FR9, FR10: a program stage naming a declared executor is sound
    def "a program stage naming a declared executor yields no error"() {
        expect:
        ExecutorReferenceRule.validate(pipeline([
            stage('lint', ExecutorType.PROGRAM, 'acme')
        ], [
            declared('lint-bot'),
            declared('acme')
        ])) == []
    }

    // FR9, D8 (task 7.3): a declared executor runs only a program stage — the dispatch keys an agent
    // stage by its type, so a name on one would pin and report an executor that never ran
    // Scenario: pipeline/pipeline-config / Non-program stage naming a declared executor is rejected
    def "a non-program stage naming a declared executor is a located error"() {
        expect:
        ExecutorReferenceRule.validate(pipeline([stage('plan', type, 'acme')], [declared('acme')])) == [
            new ConfigError('stages/plan/stage.yaml', 'executor.name',
            "executor 'acme' is a declared executor, which only a program stage runs; declare executor.type program or drop executor.name")
        ]

        where:
        type << [
            ExecutorType.API,
            ExecutorType.AGENT_CLI
        ]
    }

    // FR9: an agent stage that names nothing needs no declaration
    def "an agent stage naming no executor yields no error"() {
        expect:
        ExecutorReferenceRule.validate(pipeline([stage('plan', type, null)], [])) == []

        where:
        type << [
            ExecutorType.API,
            ExecutorType.AGENT_CLI
        ]
    }

    // FR9: the unknown name is quoted, with the declared set
    // Scenario: pipeline/pipeline-config / Unknown executor name is a located error
    def "a stage naming an undeclared executor is a located error naming the stage and the name"() {
        expect:
        ExecutorReferenceRule.validate(pipeline([
            stage('lint', ExecutorType.PROGRAM, 'acme')
        ], declarations)) == [
            new ConfigError('stages/lint/stage.yaml', 'executor.name', message)
        ]

        where:
        declarations || message
        [] || "unknown executor 'acme'; config.yaml declares no executors"
        [
            declared('lint-bot'),
            declared('x')
        ] || "unknown executor 'acme'; declared executors are lint-bot, x"
    }

    // FR10: a program stage has no other way to say which program it runs
    // Scenario: pipeline/pipeline-config / Program stage without a name is rejected
    def "a program stage without executor.name is a located error naming the stage and the field"() {
        expect:
        ExecutorReferenceRule.validate(pipeline([
            stage('lint', ExecutorType.PROGRAM, null)
        ], [declared('acme')])) == [
            new ConfigError('stages/lint/stage.yaml', 'executor.name',
            "missing required field 'executor.name'; a program stage names the executor declaration it runs")
        ]
    }

    // FR9: every stage is judged, in pipeline order
    def "errors follow pipeline order"() {
        when:
        def errors = ExecutorReferenceRule.validate(pipeline([
            stage('a', ExecutorType.PROGRAM, 'ghost'),
            stage('b', ExecutorType.AGENT_CLI, null),
            stage('c', ExecutorType.PROGRAM, null)
        ], []))

        then:
        errors*.file == [
            'stages/a/stage.yaml',
            'stages/c/stage.yaml'
        ]
    }

    // FR9: the rule runs inside the pure aggregator
    def "the pipeline validator reports the reference error"() {
        expect:
        PipelineValidator.validate(pipeline([
            stage('lint', ExecutorType.PROGRAM, 'acme')
        ], []), [] as Set)
        .any { it.where() == 'executor.name' }
    }
}
