package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
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
 * built-in as one entry beside the law's declarations; the dispatch runs it through the shared round
 * wrapper over the entry's factory, opening its rounds through the run's one round source (design D18
 * of migrate-verify-to-executor-contract: entries bound at resolution). A command round is
 * observed by the process it launches in the round's real host environment, whose launch hands back a
 * {@link FakeProcess} that has already exited; an agent round, since task 5.1a of
 * extract-agent-round-runner, by the fake dialect's CLI the {@code claude} entry launches in the same
 * environment. The mixed pipeline is driven through the run's real {@link ExecutorDispatch}, whose
 * {@code COMMAND} arm maps a command stage's type to this entry's name.
 */
@Timeout(30)
class ExecutorRegistryCommandSpec extends Specification {

    @TempDir
    Path workingCopy

    def clock = new VirtualClock()
    def law = PipelineLaw.ofContent(['instructions.md': 'build it'])
    List<List<String>> launched = []
    List<StageExecutor.Request> opened = []
    HostTaskExecutionEnvironment host

    def setup() {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            launched << builder.command()
            if (builder.command().first() == 'fake-agent') {
                return new FakeProcess(0, 'hello m s\ndone ok\n') {}
            }
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
            }, answerFilePath: {
                Path.of('answer.json')
            }, answerEnvFragment: {
                [:]
            }, closeRound: { }, discard: { }] as RoundEnvironmentSource.Round
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
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [(declared): ProgramInBox.describe()])
        ExecutorRegistry.of(described, definition(declared), law, equipment, BuiltInEntriesFixture.of(clock), null,
                AgentEntryFixture.idle(equipment, law))
    }

    private static StageExecutor.Request request(StageDefinition stage) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        stage, new Workspace() {}, 0, [])
    }

    def "FR8: the dispatch resolves the built-in name to the command built-in, which runs the stage's declared command in the round the run's round source opens"() {
        given:
        def build = request(stage('build', ExecutorType.COMMAND, './gradlew build'))

        when:
        def result = new ExecutorDispatch(registry(), rounds()).execute(build)

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
    // FR14 of extract-agent-round-runner (task 5.1a): the agent stage runs on the claude entry, the
    //     command stage on the built-in, each through the run's one round source
    def "FR8: in a mixed pipeline each stage runs through the dispatch: the agent stage on the claude entry, the command stage on the built-in"() {
        given: 'the run dispatch over the registry, built once at wiring'
        def registry = registry()
        def dispatch = new ExecutorDispatch(registry, rounds())
        def implement = request(stage('implement', ExecutorType.AGENT_CLI, null))
        def build = request(stage('build', ExecutorType.COMMAND, './gradlew build'))

        when:
        def implemented = dispatch.execute(implement)
        def built = dispatch.execute(build)

        then: 'each stage opened its round through the run round source'
        opened == [implement, build]

        and: 'the agent stage launched the claude entry\'s CLI and completed'
        launched[0] == ['fake-agent', 'transform']
        implemented.status() instanceof ExecutionResult.Completed

        and: 'the command stage ran the declared command on the built-in'
        launched.size() == 2
        launched[1].last() == './gradlew build'
        built.status() instanceof ExecutionResult.Completed
    }

    // Scenario: executor/command-executor / A law declaration named after the built-in is refused
    def "FR8, D18: a law that declares an executor under the built-in name '#name' is refused when the registry is built"() {
        when:
        registry(name)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "the law declares an executor named '${name}', a built-in's name; rename the declaration"

        where:
        name << BuiltInExecutors.NAMES
    }
}
