package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * StageDefinition: the immutable model of one pipeline stage, mirroring the
 * eight IDEF0/ICOM + Quality Control sections of the stage contract
 * (.claude/rules/stage-description.md). Lists are order-preserving and
 * defensively copied; the mechanism carries an opaque model/settings pair
 * (Q1/D5a) whose blank-model sanity is a located pure-validator concern
 * (FR11, task 4.4), never a constructor exception.
 * Implements FR2 of load-pipeline-config.
 */
class StageDefinitionSpec extends Specification {

    private static final ArtifactOutput OUTPUT = new ArtifactOutput('impl-diff')
    private static final VerifyCheck CHECK = new VerifyCheck.Command('./gradlew check')

    private static StageDefinition.Executor anExecutor(Map<String, Object> settings = [temperature: 0]) {
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', settings)
    }

    private static StageDefinition stageWith(
            List<ArtifactInput> inputs, List<ArtifactOutput> outputs, List<VerifyCheck> verify) {
        new StageDefinition(
                'implement', 'Turn the plan into a verified implementation',
                inputs, outputs, anExecutor(), 'stages/implement/instructions.md',
                verify, new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static StageDefinition fullStage() {
        stageWith([new ArtifactInput.Source()], [OUTPUT], [CHECK])
    }

    // FR2: one typed component per stage-contract section, exposed exactly;
    // list equality is order-sensitive, so declaration order is asserted too
    def "a stage exposes the eight stage-contract sections as typed components"() {
        given: 'the section values of one stage'
        def plan = new ArtifactInput.Internal('plan')
        def report = new ArtifactOutput('test-report')
        def mechanism = anExecutor()
        def limits = new AutonomyLimits(3)
        def inputs = [
            plan,
            new ArtifactInput.Source()
        ]
        def outputs = [OUTPUT, report]

        when: 'a stage is modeled from them'
        def stage = new StageDefinition(
                'implement', 'Turn the plan into a verified implementation',
                inputs as List<ArtifactInput>, outputs, mechanism, 'stages/implement/instructions.md',
                [CHECK], limits, AdvancementMode.MANUAL)

        then: 'each component is exposed exactly, lists in declaration order'
        stage.name() == 'implement'
        stage.purpose() == 'Turn the plan into a verified implementation'
        stage.inputs() == inputs
        stage.outputs() == outputs
        stage.executor() == mechanism
        stage.instructionsRef() == 'stages/implement/instructions.md'
        stage.verify() == [CHECK]
        stage.limits() == limits
        stage.advancement() == AdvancementMode.MANUAL
    }

    // FR2 delta-spec scenario: the ordered position of each check is preserved
    def "the ordered position of each verify check within the stage is preserved"() {
        given: 'a verify list with all four check types, cheap deterministic first'
        def checks = [
            new VerifyCheck.Builtin('files_exist', [paths: ['README.md']]),
            new VerifyCheck.Command('./gradlew check'),
            new VerifyCheck.External('ci/build', Duration.ofSeconds(30), Duration.ofMinutes(15)),
            new VerifyCheck.Judge('stages/implement/acceptance.md', 'claude-sonnet-4-5', [:], 3),
        ]

        expect: 'the stage presents the checks in exactly the declared order'
        stageWith([new ArtifactInput.Source()], [OUTPUT], checks as List<VerifyCheck>).verify() == checks
    }

    // FR2: the model is immutable — defensive copies isolate from the sources
    def "stage is isolated from later mutation of the source lists"() {
        given: 'mutable source lists'
        def inputs = [new ArtifactInput.Source()]
        def outputs = [OUTPUT]
        def checks = [CHECK]

        when: 'the stage is created and every source list grows afterwards'
        def stage = stageWith(inputs, outputs, checks)
        inputs << new ArtifactInput.Internal('later noise')
        outputs << new ArtifactOutput('later-noise')
        checks << new VerifyCheck.Command('later noise')

        then: 'the stage still holds only the original elements'
        stage.inputs() == [new ArtifactInput.Source()]
        stage.outputs() == [OUTPUT]
        stage.verify() == [CHECK]
    }

    // FR2: the exposed lists themselves cannot be mutated
    def "the exposed #section list is immutable"() {
        given: 'a fully modeled stage'
        def stage = fullStage()

        when: 'a caller tries to add into the exposed list'
        list(stage) << intruder

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)

        where:
        section   | list             | intruder
        'inputs'  | { it.inputs() }  | new ArtifactInput.Source()
        'outputs' | { it.outputs() } | new ArtifactOutput('intruder')
        'verify'  | { it.verify() }  | new VerifyCheck.Command('intruder')
    }

    // FR2/Q1: the mechanism section is executor type + pinned model + opaque settings
    def "Executor exposes the executor type, pinned model and opaque settings"() {
        when: 'a mechanism is modeled'
        def mechanism = new StageDefinition.Executor(
                ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [permissionMode: 'acceptEdits'])

        then: 'the record exposes exactly the three mechanism fields'
        mechanism.type() == ExecutorType.AGENT_CLI
        mechanism.model() == 'claude-sonnet-4-5'
        mechanism.settings() == [permissionMode: 'acceptEdits']
    }

    // FR2: the model is immutable — defensive copy isolates from the source map
    def "Executor is isolated from later mutation of the source settings"() {
        given: 'a mutable source settings map'
        def source = [temperature: 0]

        when: 'the mechanism is created and the source map grows afterwards'
        def mechanism = anExecutor(source)
        source.intruder = 'later noise'

        then: 'the mechanism still holds only the original settings'
        mechanism.settings() == [temperature: 0]
    }

    // FR2: the exposed settings map itself cannot be mutated
    def "Executor's settings map is immutable"() {
        given: 'a mechanism with empty settings'
        def mechanism = anExecutor([:])

        when: 'a caller tries to put into the exposed map'
        mechanism.settings().put('intruder', true)

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR11/Q1/D6: model non-blankness belongs to the pure validators (task 4.4)
    // as a located ConfigError — the record must carry a blank model so the
    // validator can still see and report it
    def "Executor carries a blank model ('#model') without throwing"() {
        when: 'a mechanism is modeled with a model that violates FR11'
        def mechanism = new StageDefinition.Executor(ExecutorType.API, model, [:])

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        mechanism.model() == model

        where:
        model << ['', '   ']
    }

    // FR2/D5a: the domain enums are closed and pure — the yaml wire values
    // ('api'/'agent-cli', 'auto'/'manual') are the adapter's mapping concern
    def "the executor-type and advancement-mode enums are closed"() {
        expect: 'each enum holds exactly its two contract values'
        ExecutorType.values()*.name() == ['API', 'AGENT_CLI']
        AdvancementMode.values()*.name() == ['AUTO', 'MANUAL']
    }

    // FR2: stages are plain values — PipelineDefinition compares by content
    def "stages with the same components are equal values"() {
        expect: 'two independently constructed stages with equal fields are equal'
        fullStage() == fullStage()
    }
}
