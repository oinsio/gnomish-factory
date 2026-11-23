package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR9, FR10 of define-executor-contract (design D8): the run's one stage executor sends a {@code
 * program} stage to the executor its {@code executor.name} resolves to in the {@link ExecutorRegistry}
 * — which opens the round through the run's round source — a {@code command} stage to the built-in the
 * registry holds under its name (FR8 of add-command-executor, design D6), and an agent stage to the
 * reserved {@code claude} entry (FR14 of extract-agent-round-runner, task 5.1a) — every arm by the
 * round it opens through the run's round source, and by the entry the registry resolved: a name the
 * run did not register is refused before any round opens. No stage reaches an executor beside the
 * registry.
 */
class ExecutorDispatchSpec extends Specification {

    /** Thrown by the round source, so the spec sees which request opened a program round. */
    static final class Opened extends RuntimeException {
        final StageExecutor.Request request

        Opened(StageExecutor.Request request) {
            super('round opened')
            this.request = request
        }
    }

    def rounds = [openRound: { StageExecutor.Request request ->
            throw new Opened(request)
        }, identity: {
            RoundIdentity.inPlace()
        }] as RoundEnvironmentSource
    def law = PipelineLaw.ofContent(['instructions.md': 'build it'])

    private static StageDefinition stage(ExecutorType type, String name) {
        new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(type, 'model-x', [:], Sandbox.none(), name),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static StageDefinition commandStage(String command) {
        new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.COMMAND, '', [:], Sandbox.none(), null,
        new CommandLine(command)), 'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition(String... declared) {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage(ExecutorType.PROGRAM, 'acme')
        ], null,
        declared.collect {
            new ExecutorDeclaration(it, ExecutorDeclaration.Type.PROGRAM, ["/bin/$it"], [:], [], [])
        })
    }

    private ExecutorDispatch dispatch(Map answers, String... declared) {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.create(),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), answers)
        new ExecutorDispatch(ExecutorRegistry.of(described, definition(declared), law, equipment,
                BuiltInEntriesFixture.of(new VirtualClock()), null, AgentEntryFixture.idle(equipment, law)), rounds)
    }

    private static StageExecutor.Request request(StageDefinition stage) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        stage, new Workspace() {}, 0, [])
    }

    def "FR10: a program stage runs the executor its name resolves to, through the run's round source"() {
        given:
        def build = request(stage(ExecutorType.PROGRAM, 'acme'))

        when:
        dispatch([acme: ProgramInBox.describe(), other: ProgramInBox.describe()], 'other', 'acme').execute(build)

        then:
        def opened = thrown(Opened)
        opened.request.is(build)
    }

    // FR1, FR14 of extract-agent-round-runner (D12, task 5.1a): no type-keyed agent arm is left — the
    //     agent stage resolves the reserved claude entry by name and opens its round like any other
    def "FR14: a #type stage resolves the claude entry and opens its round through the run's round source"() {
        given:
        def build = request(stage(type, null))

        when:
        dispatch([acme: ProgramInBox.describe()], 'acme').execute(build)

        then:
        def opened = thrown(Opened)
        opened.request.is(build)

        where:
        type << [
            ExecutorType.AGENT_CLI,
            ExecutorType.API
        ]
    }

    def "FR9: a program stage whose name the run did not register is refused, naming it and what was registered"() {
        when: 'the preflight described acme only, a run that ran no stage describes nothing'
        dispatch(answers, 'acme', 'lint').execute(request(stage(ExecutorType.PROGRAM, 'lint')))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "no executor named 'lint' is registered for this run; registered: ${registered}"

        where:
        answers || registered
        [acme: ProgramInBox.describe()] || '[acme, claude, command]'
        [:] || '[claude, command]'
    }

    // FR8 of add-command-executor (design D6): the COMMAND arm maps the stage type to the built-in's
    //     registry name, so the round opens through the run's round source
    def "FR8: a command stage runs the command built-in the registry resolves, through the run's round source"() {
        given:
        def build = request(commandStage('./gradlew build'))

        when:
        dispatch([acme: ProgramInBox.describe()], 'acme').execute(build)

        then:
        def opened = thrown(Opened)
        opened.request.is(build)
    }
}
