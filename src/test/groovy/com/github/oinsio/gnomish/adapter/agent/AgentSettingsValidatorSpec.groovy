package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * Unit spec for {@link AgentSettingsValidator} (task 9.1): exactly {@code
 * allowedTools}/{@code disallowedTools}/{@code maxTurns}/{@code roundTimeout}
 * per {@code agent-cli} executor and every judge check, well-formed values,
 * located startup errors naming the stage/check and key.
 *
 * <p>FR11, UX2, D7 of add-agent-executor.
 */
class AgentSettingsValidatorSpec extends Specification {

    private static final AutonomyLimits LIMITS = new AutonomyLimits(2)

    def "an agent-cli stage with fully well-formed settings yields no errors"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [
            allowedTools   : ['Read', 'Write'],
            disallowedTools: ['Bash'],
            maxTurns       : 5,
            roundTimeout   : 30,
        ], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == []
    }

    def "an api stage's settings are never inspected, however malformed"() {
        given:
        def stage = stage('plan', ExecutorType.API, [allowedTools: 'not-a-list', bogus: 1], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == []
    }

    def "an unrecognized key on an agent-cli executor is a located error naming the stage and key"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [allowedTols: ['Read']], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == [
            new ConfigError('stages/build/stage.yaml', 'executor.settings.allowedTols',
            "unrecognized settings key 'allowedTols'")
        ]
    }

    def "a malformed value on an agent-cli executor yields exactly its located error"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [(key): value], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == [
            new ConfigError('stages/build/stage.yaml', "executor.settings.${key}".toString(), message)
        ]

        where:
        key               | value               || message
        'allowedTools'    | 'Read'              || "malformed 'allowedTools': expected a list of strings"
        'allowedTools'    | ['Read', 2]         || "malformed 'allowedTools': expected a list of strings"
        'disallowedTools' | [1, 2]              || "malformed 'disallowedTools': expected a list of strings"
        'maxTurns'        | 'five'              || "malformed 'maxTurns': expected a number"
        'roundTimeout'    | true                || "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string"
        'roundTimeout'    | 'not-a-duration'    || "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string"
    }

    def "roundTimeout accepts both a plain number of seconds and an ISO-8601 duration string"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [roundTimeout: value], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == []

        where:
        value << [30, 'PT30S', 'pt1m']
    }

    def "a judge check is validated the same way regardless of its parent stage's executor type"() {
        given:
        def judge = judgeCheck([badKey: 1])
        def stage = stage('plan', executorType, [:], [judge])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].settings.badKey',
            "unrecognized settings key 'badKey'")
        ]

        where:
        executorType << [
            ExecutorType.API,
            ExecutorType.AGENT_CLI
        ]
    }

    def "a well-formed judge check under an api stage yields no errors"() {
        given:
        def judge = judgeCheck([allowedTools: ['Read'], maxTurns: 3])
        def stage = stage('plan', ExecutorType.API, [:], [judge])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == []
    }

    def "multiple independent problems across stage executor and judge checks are all reported, in stage/check order"() {
        given:
        def judge = judgeCheck([oops: 1])
        def stage = stage('build', ExecutorType.AGENT_CLI, [maxTurns: 'nope'], [judge])

        expect:
        AgentSettingsValidator.validate(pipeline(stage)) == [
            new ConfigError('stages/build/stage.yaml', 'executor.settings.maxTurns',
            "malformed 'maxTurns': expected a number"),
            new ConfigError('stages/build/stage.yaml', 'verify[0].settings.oops',
            "unrecognized settings key 'oops'"),
        ]
    }

    private static VerifyCheck.Judge judgeCheck(Map<String, Object> settings) {
        new VerifyCheck.Judge('accept.md', 'judge-model', settings, 1)
    }

    private static StageDefinition stage(
            String name, ExecutorType type, Map<String, Object> settings, List<VerifyCheck> verify) {
        new StageDefinition(
                name,
                'purpose',
                List.<ArtifactInput> of(),
                List.<ArtifactOutput> of(),
                new StageDefinition.Executor(type, 'model', settings),
                'stages/%s/instructions.md'.formatted(name),
                verify,
                LIMITS,
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', LIMITS, List.of(stages))
    }
}
