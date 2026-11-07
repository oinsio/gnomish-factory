package com.github.oinsio.gnomish.domain.pipeline

import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks

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
    private static final VerifyCheck CHECK = VerifyChecks.command('./gradlew check')

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
            VerifyChecks.builtin('files_exist', [paths: ['README.md']]),
            VerifyChecks.command('./gradlew check'),
            VerifyChecks.external('ci/build', 'ci-platform', Duration.ofSeconds(30), Duration.ofMinutes(15), VerifyCheck.TimeoutClass.QUALITY),
            new VerifyCheck.Judge('stages/implement/acceptance.md', 'claude-sonnet-4-5', [:], 3),
        ]

        expect: 'the stage presents the checks in exactly the declared order'
        stageWith([new ArtifactInput.Source()], [OUTPUT], checks as List<VerifyCheck>).verify() == checks
    }

    // FR2: the model is immutable — defensive copies isolate from the sources
    def "stage is isolated from later mutation of the source lists"() {
        given: 'mutable source lists'
        List<ArtifactInput> inputs = [new ArtifactInput.Source()]
        def outputs = [OUTPUT]
        def checks = [CHECK]

        when: 'the stage is created and every source list grows afterwards'
        def stage = stageWith(inputs, outputs, checks)
        inputs << new ArtifactInput.Internal('later noise')
        outputs << new ArtifactOutput('later-noise')
        checks << VerifyChecks.command('later noise')

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
        section | list | intruder
        'inputs' | { it.inputs() } | new ArtifactInput.Source()
        'outputs' | { it.outputs() } | new ArtifactOutput('intruder')
        'verify' | { it.verify() } | VerifyChecks.command('intruder')
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

    // FR12/FR13 (add-sandbox-core): the Mechanism carries the sandbox
    // declarations; the three-arg convenience form defaults them to none()
    def "Executor carries the sandbox declarations, defaulting to none()"() {
        given: 'a sandbox declaration'
        def sandbox = new Sandbox(['docker-inside'], true)

        expect: 'the three-arg form defaults sandbox to none(), the four-arg form carries it'
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]).sandbox() == Sandbox.none()
        new StageDefinition.Executor(ExecutorType.API, 'model', [:], sandbox).sandbox() == sandbox
    }

    // FR2: the model is immutable — defensive copy isolates from the source map
    def "Executor is isolated from later mutation of the source settings"() {
        given: 'a mutable source settings map'
        Map<String, Object> source = [temperature: 0]

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

    // FR9 of define-executor-contract: the executor declaration a stage names rides the
    //     Mechanism; the shorter constructors name none
    def "Executor carries the named executor declaration, defaulting to none"() {
        expect:
        new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), 'acme').name() == 'acme'
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()).name() == null
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]).name() == null
    }

    // FR2/D5a: the domain enums are closed and pure — the yaml wire values
    // ('api'/'agent-cli'/'program'/'command', 'auto'/'manual') are the adapter's mapping concern;
    // PROGRAM is FR10 of define-executor-contract; COMMAND is FR1 of add-command-executor
    def "the executor-type and advancement-mode enums are closed"() {
        expect: 'each enum holds exactly its contract values'
        ExecutorType.values()*.name() == [
            'API',
            'AGENT_CLI',
            'PROGRAM',
            'COMMAND'
        ]
        AdvancementMode.values()*.name() == ['AUTO', 'MANUAL']
    }

    // FR2: stages are plain values — PipelineDefinition compares by content
    def "stages with the same components are equal values"() {
        expect: 'two independently constructed stages with equal fields are equal'
        fullStage() == fullStage()
    }
}
