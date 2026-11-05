package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.command.CommandExecutorFactory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
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
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR8 of add-command-executor (design D6): the run's {@link ExecutorRegistry} holds the {@code command}
 * built-in as one entry beside the law's declarations, built by the shared round wrapper over the
 * built-in's factory and opening its rounds through the run's one round source. A command round is
 * observed by the process it launches in the round's real host environment, whose launch hands back a
 * {@link FakeProcess} that has already exited; an agent round by the request the agent arm receives.
 * The mixed pipeline is driven through the run's real {@link ExecutorDispatch}, whose {@code COMMAND}
 * arm maps a command stage's type to this entry's name.
 */
@Timeout(30)
class ExecutorRegistryCommandSpec extends Specification {

    @TempDir
    Path workingCopy

    def clock = new VirtualClock()
    def agent = Mock(StageExecutor)
    def law = PipelineLaw.ofContent(['instructions.md': 'build it'])
    List<List<String>> launched = []
    List<StageExecutor.Request> opened = []
    HostTaskExecutionEnvironment host

    def setup() {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            launched << builder.command()
            def process = FakeProcess.running()
            process.exit(0)
            process
        } as ProcessLaunch
        host = new HostTaskExecutionEnvironment(workingCopy, clock, ChildEnvAllowlist.none(), launch)
        host.materialize('gnomish/TASK-1', null)
    }

    def cleanup() {
        host.dispose()
    }

    private RoundEnvironmentSource rounds() {
        def round = [environment: {
                host
            }, decisionFilePath: {
                null
            }, decisionEnvFragment: {
                [:]
            }, closeRound: { }, readDecision: {
                Optional.empty()
            }, discard: { }] as RoundEnvironmentSource.Round
        [openRound: { StageExecutor.Request request ->
                opened << request
                round
            }, identity: {
                RoundIdentity.inPlace()
            }] as RoundEnvironmentSource
    }

    private static StageDefinition stage(String name, ExecutorType type, String command) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(type, type == ExecutorType.COMMAND ? '' : 'model-x', [:], Sandbox.none(),
        type == ExecutorType.PROGRAM ? 'acme' : null, command == null ? null : new CommandLine(command)),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition(String declared) {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('implement', ExecutorType.AGENT_CLI, null),
            stage('build', ExecutorType.COMMAND, './gradlew build')
        ], null, [
            new ExecutorDeclaration(declared, ExecutorDeclaration.Type.PROGRAM, ["/bin/$declared"], [:], [], [])
        ])
    }

    private ExecutorRegistry registry(String declared = 'acme') {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'))
        def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [(declared): ProgramInBox.describe()])
        ExecutorRegistry.of(described, definition(declared), law, rounds(), equipment, new CommandExecutorFactory(clock))
    }

    private static StageExecutor.Request request(StageDefinition stage) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        stage, new Workspace() {}, 0, [])
    }

    def "FR8: the registry resolves the built-in name to the command built-in, which runs the stage's declared command in the round the run's round source opens"() {
        given:
        def build = request(stage('build', ExecutorType.COMMAND, './gradlew build'))

        when:
        def result = registry().resolve(new ExecutorName(CommandExecutorFactory.NAME)).execute(build)

        then: 'the round was opened by the run round source, for this request'
        opened.size() == 1
        opened[0].is(build)

        and: 'the built-in launched the declared command, once, and completed'
        launched.size() == 1
        launched[0].last() == './gradlew build'
        result.status() instanceof ExecutionResult.Completed
    }

    def "FR8: the law's declarations stay registered beside the built-in, each under its own name"() {
        given:
        def registry = registry()

        expect:
        !registry.resolve(new ExecutorName('acme')).is(registry.resolve(new ExecutorName('command')))
    }

    // Scenario: executor/command-executor / Mixed pipeline dispatches per stage
    def "FR8: in a mixed pipeline each stage runs through the dispatch: the agent stage on the agent arm, the command stage on the built-in and never on the agent arm"() {
        given: 'the run dispatch over the registry, built once at wiring'
        def registry = registry()
        def dispatch = new ExecutorDispatch(registry, agent)
        def implement = request(stage('implement', ExecutorType.AGENT_CLI, null))
        def build = request(stage('build', ExecutorType.COMMAND, './gradlew build'))
        def agentResult = ExecutionResult.completed(ExecutorUsage.none(),
                new ToolTrace(new AttemptKey('TASK-1', 'implement', 0), []), [])

        when:
        def implemented = dispatch.execute(implement)
        def built = dispatch.execute(build)

        then: 'the agent stage reached the agent arm and opened no registry round'
        1 * agent.execute(implement) >> agentResult
        implemented.is(agentResult)

        and: 'the command stage ran on the built-in, never on the agent arm'
        0 * agent.execute(build)
        opened == [build]
        launched.size() == 1
        launched[0].last() == './gradlew build'
        built.status() instanceof ExecutionResult.Completed
    }

    // Scenario: executor/command-executor / A law declaration named after the built-in is refused
    def "FR8: a law that declares an executor under the built-in's name is refused when the registry is built"() {
        when:
        registry(CommandExecutorFactory.NAME)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "the law declares an executor named 'command', the name of the built-in a command stage runs; " +
                'rename the declaration'
    }
}
