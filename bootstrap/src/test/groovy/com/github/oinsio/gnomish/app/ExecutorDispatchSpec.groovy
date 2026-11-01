package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
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
 * — which opens the round through the run's round source — and every other stage to the agent arm.
 * The program arm is observed by the round it opens, the agent arm by the request it receives.
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

    def agent = Mock(StageExecutor)
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
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'))
        def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), answers)
        new ExecutorDispatch(ExecutorRegistry.of(described, definition(declared), law, rounds, equipment), agent)
    }

    private static StageExecutor.Request request(StageDefinition stage) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        stage, new Workspace() {}, 0, [])
    }

    def "FR10: a program stage runs the executor its name resolves to, through the run's round source, and never the agent"() {
        given:
        def build = request(stage(ExecutorType.PROGRAM, 'acme'))

        when:
        dispatch([acme: ProgramInBox.describe(), other: ProgramInBox.describe()], 'other', 'acme').execute(build)

        then:
        def opened = thrown(Opened)
        opened.request.is(build)
        0 * agent._
    }

    def "FR9: a #type stage runs the agent arm, and no program round is opened"() {
        given:
        def build = request(stage(type, null))
        def result = ExecutionResult.completed(ExecutorUsage.none(),
                new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])

        when:
        def returned = dispatch([acme: ProgramInBox.describe()], 'acme').execute(build)

        then:
        1 * agent.execute(build) >> result
        returned.is(result)

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
        0 * agent._

        where:
        answers || registered
        [acme: ProgramInBox.describe()] || '[acme]'
        [:] || '[]'
    }
}
