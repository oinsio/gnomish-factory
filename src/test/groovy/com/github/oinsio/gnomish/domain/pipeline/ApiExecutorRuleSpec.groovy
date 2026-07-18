package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ApiExecutorRule: the pure fail-fast rule (design D6, NG3) that rejects any
 * stage whose executor type is `api` — this change (add-agent-executor) wires
 * only the `agent-cli` mechanism; `api` stages are rejected at startup rather
 * than silently accepted and left undispatchable at run time.
 *
 * <p>Implements FR10, UX2, D6 of add-agent-executor.
 */
class ApiExecutorRuleSpec extends Specification {

    private static StageDefinition stage(String name, ExecutorType type) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], [],
                new StageDefinition.Executor(type, "$name-model", [:]),
                "stages/$name/instructions.md",
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(2), AdvancementMode.AUTO)
    }

    def "an api-executor stage yields exactly one located error naming the stage manifest and executor.type"() {
        given:
        def stages = [
            stage('plan', ExecutorType.API)
        ]

        when:
        def errors = ApiExecutorRule.validate(stages)

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type',
            "api executor is not yet supported; 'agent-cli' is the only supported executor type currently")
        ]
    }

    def "an agent-cli-executor stage yields no error"() {
        given:
        def stages = [
            stage('build', ExecutorType.AGENT_CLI)
        ]

        expect:
        ApiExecutorRule.validate(stages) == []
    }

    def "a mixed pipeline reports the error only for the api stage, in pipeline order"() {
        given:
        def stages = [
            stage('plan', ExecutorType.API),
            stage('build', ExecutorType.AGENT_CLI),
            stage('review', ExecutorType.API)
        ]

        when:
        def errors = ApiExecutorRule.validate(stages)

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.type',
            "api executor is not yet supported; 'agent-cli' is the only supported executor type currently"),
            new ConfigError('stages/review/stage.yaml', 'executor.type',
            "api executor is not yet supported; 'agent-cli' is the only supported executor type currently")
        ]
    }

    def "an empty stage list yields no error"() {
        expect:
        ApiExecutorRule.validate([]) == []
    }

    def "the returned error list is immutable"() {
        given:
        def errors = ApiExecutorRule.validate([
            stage('plan', ExecutorType.API)
        ])

        when:
        errors.add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }
}
