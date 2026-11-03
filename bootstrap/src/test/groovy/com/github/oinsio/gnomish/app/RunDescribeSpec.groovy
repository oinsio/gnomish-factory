package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RunDescribe}, the one owner of a run's described law (FR4, FR13 of define-executor-contract;
 * design D5, as revised 2026-10-31), over the real assembly: on the host it describes in an
 * environment over the run's directory and disposes it after, handing the answer back as the law
 * every assemble takes; a law that declares no executor, and a run past the pipeline's end, are
 * described with no environment and nothing spawned.
 */
class RunDescribeSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path tempDir

    def "FR4, FR13: on the host the law is described in an environment over the run's directory, disposed after"() {
        given:
        def workspace = Files.createDirectories(tempDir.resolve('work'))
        def program = StandIn.recording(tempDir, 'reference-recorded')
        def binding = LawBinding.workingTree(workspace)

        when:
        def described = assembly().describe(order(law([program])), new DescribeMedium.Host(workspace), [], binding)

        then: 'the law described, its version under the executor name, on the binding it was asked for'
        described instanceof DescribedLaw
        (described as DescribedLaw).binding() == binding
        (described as DescribedLaw).executors().all().of('acme').get().forParsing() == '1'

        and: 'the answer went to the environment\'s scratch area, outside the working copy, removed with it'
        def resultFile = Path.of((StandIn.log(program).toFile().text =~ /GNOMISH_RESULT_FILE=\[(.*)]/)[0][1] as String)
        !resultFile.startsWith(workspace)
        !Files.exists(resultFile.parent)
    }

    def "FR13: #what is described with no environment and nothing spawned"() {
        given:
        def program = StandIn.recording(tempDir, 'reference-recorded')

        when:
        def described = assembly().describe(order(law(programs.collect {
            program
        })), medium, [],
        LawBinding.workingTree(tempDir))

        then:
        (described as DescribedLaw).executors() == DescribedExecutors.none()
        !Files.exists(StandIn.log(program))

        where:
        what | programs | medium
        'a law that declares no executor' | [] | new DescribeMedium.Host(Path.of('/nonexistent'))
        'a run past the pipeline\'s end' | ['acme'] | new DescribeMedium.NoStageRuns()
    }

    def "FR4: in a box the law is described in the task's live box for the stage the run starts in"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        assembly().describe(order(law([Path.of('/opt/acme')])), new DescribeMedium.Box(support, 'build'), [],
        LawBinding.workingTree(tempDir))

        then:
        1 * support.liveEnvironment('build') >> {
            throw new IllegalStateException('the box')
        }
        thrown(IllegalStateException)
    }

    private ManualRunAssembly assembly() {
        newAssembly(FakeAgentSupport.propertiesFor('plain-round'))
    }

    private RunOrder order(PipelineDefinition definition) {
        new RunOrder(tempDir, null, definition, false)
    }

    private static PipelineDefinition law(List programs) {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(programs ? ExecutorType.PROGRAM : ExecutorType.AGENT_CLI, 'model-x', [:], Sandbox.none(), programs ? 'acme' : null),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, programs.collect {
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [it.toString()], [:], [], [])
        })
    }
}
