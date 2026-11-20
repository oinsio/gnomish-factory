package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import spock.lang.Specification

/**
 * Unit spec for {@link AgentSettingsValidator}: which settings reach the agent executor's settings
 * judge, and where each is located — every {@code agent-cli} stage executor's and every judge
 * check's, whatever the judge check's parent stage. What the settings may hold is the judge's (the
 * Claude dialect's schema, {@code ClaudeSettingsSpec} in {@code :adapters:agent}).
 *
 * <p>FR11, UX2, D7 of add-agent-executor; FR7, D6 of extract-agent-round-runner.
 */
class AgentSettingsValidatorSpec extends Specification {

    private static final AutonomyLimits LIMITS = new AutonomyLimits(2)

    /** A judge that reports one error per settings map it is handed, naming where it was. */
    final List<List<Object>> judged = []
    final CheckSettingsValidator judge = { String file, String where, Map<String, Object> settings ->
        judged << [file, where, settings]
        [
            new ConfigError(file, where, 'judged')
        ]
    } as CheckSettingsValidator

    def "FR7: an agent-cli stage's executor settings reach the judge, located at executor.settings"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [maxTurns: 5], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage), judge) == [
            new ConfigError('stages/build/stage.yaml', 'executor.settings', 'judged')
        ]
        judged == [
            [
                'stages/build/stage.yaml',
                'executor.settings',
                [maxTurns: 5]
            ]
        ]
    }

    def "FR7: a #type stage's executor settings never reach the judge"() {
        given:
        def stage = stage('plan', type, [allowedTools: 'not-a-list', bogus: 1], [])

        expect:
        AgentSettingsValidator.validate(pipeline(stage), judge) == []
        judged.isEmpty()

        where:
        type << [
            ExecutorType.API,
            ExecutorType.COMMAND
        ]
    }

    def "FR7: a judge check's settings reach the judge whatever its stage's executor type (#type)"() {
        given:
        def stage = stage('plan', type, [:], [
            command(),
            judgeCheck([maxTurns: 3])
        ])

        when:
        def errors = AgentSettingsValidator.validate(pipeline(stage), judge)

        then: 'located at the check\'s own index; the command check is not the agent\'s'
        errors.last() == new ConfigError('stages/plan/stage.yaml', 'verify[1].settings', 'judged')
        judged.last() == [
            'stages/plan/stage.yaml',
            'verify[1].settings',
            [maxTurns: 3]
        ]

        where:
        type << [
            ExecutorType.API,
            ExecutorType.AGENT_CLI,
            ExecutorType.COMMAND
        ]
    }

    def "every problem is reported, in stage then check order"() {
        given:
        def first = stage('build', ExecutorType.AGENT_CLI, [:], [judgeCheck([:])])
        def second = stage('review', ExecutorType.AGENT_CLI, [:], [])

        expect:
        AgentSettingsValidator.validate(pipeline(first, second), judge)*.where() == [
            'executor.settings',
            'verify[0].settings',
            'executor.settings'
        ]
        AgentSettingsValidator.validate(pipeline(first, second), judge)*.file() == [
            'stages/build/stage.yaml',
            'stages/build/stage.yaml',
            'stages/review/stage.yaml'
        ]
    }

    def "a load handed no judge grades no agent settings"() {
        given:
        def stage = stage('build', ExecutorType.AGENT_CLI, [bogus: 1], [judgeCheck([bogus: 1])])

        expect:
        AgentSettingsValidator.validate(pipeline(stage), AgentSettingsValidator.UNJUDGED) == []
    }

    def "the reported list is immutable"() {
        when:
        AgentSettingsValidator.validate(pipeline(stage('build', ExecutorType.AGENT_CLI, [:], [])), judge)
        .add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }

    private static VerifyCheck.Judge judgeCheck(Map<String, Object> settings) {
        new VerifyCheck.Judge('accept.md', 'judge-model', settings, 1)
    }

    private static VerifyCheck command() {
        VerifyChecks.command('true')
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
