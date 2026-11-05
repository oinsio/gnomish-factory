package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * Unit spec for {@link CommandSettingsValidator}: a {@code command} stage's executor settings
 * accept exactly {@code roundTimeout}, judged by the agent-cli well-formedness check, and every
 * other stage's executor settings are left to their own owner.
 *
 * <p>FR3, UX2 of add-command-executor.
 */
class CommandSettingsValidatorSpec extends Specification {

    private static final AutonomyLimits LIMITS = new AutonomyLimits(2)
    private static final String MALFORMED =
    "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string"

    def "FR3: a command stage's well-formed roundTimeout (#value) yields no errors"() {
        expect:
        CommandSettingsValidator.validate(pipeline(commandStage('gen', [roundTimeout: value]))) == []

        where:
        value << [
            600,
            1.5,
            'PT10M',
            'pt1m',
            '30s'
        ]
    }

    def "FR3: a command stage with no settings yields no errors"() {
        expect:
        CommandSettingsValidator.validate(pipeline(commandStage('gen', [:]))) == []
    }

    def "FR3, UX2: an unrecognized key (#key) on a command stage is a located error naming the stage and key"() {
        expect:
        CommandSettingsValidator.validate(pipeline(commandStage('gen', [(key): value]))) == [
            new ConfigError('stages/gen/stage.yaml', "executor.settings.${key}".toString(),
            "unrecognized settings key '${key}'".toString())
        ]

        where:
        key | value
        'allowedTools' | ['Read']
        'maxTurns' | 5
        'roundTimout' | 30
    }

    def "FR3, UX2: a malformed roundTimeout (#value) is a located error naming the stage and key"() {
        expect:
        CommandSettingsValidator.validate(pipeline(commandStage('gen', [roundTimeout: value]))) == [
            new ConfigError('stages/gen/stage.yaml', 'executor.settings.roundTimeout', MALFORMED)
        ]

        where:
        value << [
            true,
            'not-a-duration',
            '',
            '   ',
            ['PT1M']
        ]
    }

    def "FR3: every problem across command stages is reported, in stage order and key-name order within a stage"() {
        given:
        def first = commandStage('gen', [roundTimeout: 'soon', bogus: 1])
        def second = commandStage('lint', [maxTurns: 2])

        expect:
        CommandSettingsValidator.validate(pipeline(first, second)) == [
            new ConfigError('stages/gen/stage.yaml', 'executor.settings.bogus', "unrecognized settings key 'bogus'"),
            new ConfigError('stages/gen/stage.yaml', 'executor.settings.roundTimeout', MALFORMED),
            new ConfigError('stages/lint/stage.yaml', 'executor.settings.maxTurns', "unrecognized settings key 'maxTurns'"),
        ]
    }

    def "FR3: a #type stage's executor settings are not this validator's to judge"() {
        given:
        def stage = stage('build', new StageDefinition.Executor(type, 'model', [allowedTools: ['Read'], bogus: 1]), [])

        expect:
        CommandSettingsValidator.validate(pipeline(stage)) == []

        where:
        type << [
            ExecutorType.API,
            ExecutorType.AGENT_CLI,
            ExecutorType.PROGRAM
        ]
    }

    def "FR3: a judge check on a command stage is not this validator's to judge"() {
        given:
        def judge = new VerifyCheck.Judge('accept.md', 'judge-model', [allowedTools: ['Read']], 1)
        def stage = stage('gen', commandExecutor([:]), [judge])

        expect:
        CommandSettingsValidator.validate(pipeline(stage)) == []
    }

    private static StageDefinition commandStage(String name, Map<String, Object> settings) {
        stage(name, commandExecutor(settings), [])
    }

    private static StageDefinition.Executor commandExecutor(Map<String, Object> settings) {
        new StageDefinition.Executor(ExecutorType.COMMAND, '', settings, Sandbox.none(), null, new CommandLine('make gen'))
    }

    private static StageDefinition stage(String name, StageDefinition.Executor executor, List<VerifyCheck> verify) {
        new StageDefinition(
                name,
                'purpose',
                List.<ArtifactInput> of(),
                List.<ArtifactOutput> of(),
                executor,
                'stages/%s/instructions.md'.formatted(name),
                verify,
                LIMITS,
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', LIMITS, List.of(stages))
    }
}
