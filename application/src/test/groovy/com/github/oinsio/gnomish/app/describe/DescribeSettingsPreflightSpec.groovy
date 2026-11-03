package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition

/**
 * The describe preflight holding a law's executor settings to the schema the executor's describe
 * declares (FR9, UX1, NFR-S2 of define-executor-contract; design D5, phase b): each violation a
 * located error at the settings path, naming the executor and the schema rule, before the first round.
 */
class DescribeSettingsPreflightSpec extends DescribePreflightSpecBase {

    // Scenario: pipeline/pipeline-config / Settings typo is caught before the first round
    def "FR9, UX1: a typo in a settings property is a located error at the settings path, naming the executor, the property and the schema rule"() {
        when:
        def outcome = preflight().describe(environment,
                law(['acme': describeAs('strict-settings')], [acme: [maxTurn: 3]], stage('build', 'acme')))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme.settings.maxTurn',
            "executor 'acme' settings break its schema's rule 'additionalProperties': "
            + "property 'maxTurn' is not defined in the schema and the schema does not allow additional properties")
        ])
    }

    def "FR9: settings the executor's schema allows are described as usual"() {
        expect:
        preflight().describe(environment,
                law(['acme': describeAs('strict-settings')], [acme: [maxTurns: 3]], stage('build', 'acme'))) instanceof DescribeOutcome.Described
    }

    def "FR9, NFR-S2: a describe whose settings schema would load another document is the program's configuration error"() {
        when:
        def outcome = preflight().describe(environment,
                law(['acme': describeAs('remote-schema')], [acme: [maxTurns: 3]], stage('build', 'acme')))

        then:
        def error = only(outcome)
        error.where() == 'executors.acme.program'
        error.message().startsWith("executor 'acme' cannot be described (configuration error): "
                + "its describe declares a settings schema the factory cannot use (Schema from "
                + "'https://schemas.example/acme-settings.json' is not allowed to be loaded.)")
    }

    def "FR4: an answer in another protocol is not held to its settings schema; only the protocol is reported"() {
        given: 'protocol-2 declares an empty schema, so any settings would pass it; the strict one would refuse these'
        def outcome = preflight().describe(environment,
                law(['acme': describeAs('protocol-2')], [acme: [maxTurn: 3]], stage('build', 'acme')))

        expect:
        outcome instanceof DescribeOutcome.Invalid
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == ['executors.acme']
    }

    private static StageDefinition stageSending(String name, String model, Map<String, Object> settings) {
        new StageDefinition(name, "Purpose of $name", [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.PROGRAM, model, settings, Sandbox.none(), 'acme'),
        "stages/$name/instructions.md", [], new AutonomyLimits(2), AdvancementMode.AUTO)
    }

    // FR9 (task 7.1, obligation 4): the judged map is the sent map — the declaration's settings under the stage's, and the model
    def "FR9: the settings a stage sends are judged, each violation located where it was written"() {
        when: 'the declaration is valid alone, but the stage adds a typo and a model the strict schema does not know'
        def outcome = preflight().describe(environment, law(['acme': describeAs('strict-settings')], [acme: [maxTurns: 3]],
        stageSending('build', 'acme-model-1', [maxTurn: 4])))

        then:
        outcome instanceof DescribeOutcome.Invalid
        ((DescribeOutcome.Invalid) outcome).errors().collect {
            "${it.file()} ${it.where()}"
        } as Set == [
            'stages/build/stage.yaml executor.settings.maxTurn',
            'stages/build/stage.yaml executor.model'
        ] as Set
    }

    // FR9: a stage's value replaces the declaration's, so a declaration value the stage overrides is not judged
    def "FR9: a stage value replaces the declaration's before the judgement"() {
        expect:
        preflight().describe(environment, law(['acme': describeAs('strict-settings')], [acme: [maxTurns: 0]],
        stageSending('build', '', [maxTurns: 5]))) instanceof DescribeOutcome.Described
    }

    // FR9, UX1: one declaration error every stage sends is reported once
    def "FR9: a declaration error two stages send is one located error"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('strict-settings')], [acme: [maxTurn: 3]],
        stageSending('build', '', [:]), stageSending('test', '', [:])))

        then:
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executors.acme.settings.maxTurn'
        ]
    }

    // FR9: only the stages that name the executor send it settings; an agent stage's settings are not its
    def "FR9: a stage that does not name the executor sends it nothing"() {
        given:
        def agent = new StageDefinition('review', 'Purpose of review', [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-x', [maxTurn: 1], Sandbox.none(), null),
        'stages/review/instructions.md', [], new AutonomyLimits(2), AdvancementMode.AUTO)

        expect:
        preflight().describe(environment, law(['acme': describeAs('strict-settings')], [acme: [maxTurns: 3]],
        stageSending('build', '', [:]), agent)) instanceof DescribeOutcome.Described
    }
}
