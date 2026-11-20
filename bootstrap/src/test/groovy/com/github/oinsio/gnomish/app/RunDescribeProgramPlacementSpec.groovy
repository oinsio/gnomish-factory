package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR3 of migrate-verify-to-executor-contract (design D18, "A {@code program} check outside a
 * fresh box"; proposal NG14): {@link RunDescribe} refuses, over the real assembly, a law whose check
 * names one of its programs where no fresh box can bind it — on the host, or {@code same-box} in a box
 * — with a located error on the describe refusal's path, before any process of the program is spawned,
 * its describe included (the program is a recording stand-in whose log stays absent); a manual {@code
 * run} exits as for an invalid law. The {@code take} and {@code serve} path is {@link
 * TakeProgramPlacementRefusalSpec}'s.
 */
class RunDescribeProgramPlacementSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path program

    def setup() {
        program = StandIn.recording(Files.createDirectories(tempDir.resolve('bin')), 'reference-recorded')
    }

    private RunOrder order(PipelineDefinition definition) {
        new RunOrder(tempDir, null, definition, false)
    }

    private PipelineDefinition law(VerifyCheck.VerifyIn verifyIn) {
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'acme', 'lint', [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], verifyIn, Threshold.ERROR, [])
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]), 'instructions.md', [check],
        new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program.toString()], [:], [], [])
        ])
    }

    private LawDescription describe(VerifyCheck.VerifyIn verifyIn, DescribeMedium medium) {
        newAssembly(FakeAgentSupport.propertiesFor('plain-round')).describe(order(law(verifyIn)), medium, [],
        LawBinding.workingTree(tempDir))
    }

    def "FR3: #medium refuses the program check, located, naming the check and the executor, and spawns nothing"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        def described = describe(verifyIn, box ? new DescribeMedium.Box(support, 'build') : new DescribeMedium.Host(tempDir))

        then:
        def errors = (described as LawDescription.Refused).errors()
        errors*.file() == ['stages/build/stage.yaml']
        errors*.where() == ['verify[0]']
        errors[0].message().contains("check 'lint' names program executor 'acme'")
        !Files.exists(StandIn.log(program))
        0 * support._

        where:
        medium | box | verifyIn
        'a host run' | false | VerifyCheck.VerifyIn.FRESH_BOX
        'a same-box check in a box' | true | VerifyCheck.VerifyIn.SAME_BOX
    }

    def "FR3: a run past the pipeline's end runs no check, so its program check is not judged"() {
        expect:
        describe(VerifyCheck.VerifyIn.SAME_BOX, new DescribeMedium.NoStageRuns()) instanceof DescribedLaw
    }

    // Scenario: verification/verdict-executors / A program verdict check outside a fresh box is a located error
    def "FR1, FR3, UX1: on run the program check outside a fresh box is the located error exit of an invalid law"() {
        when:
        ManualRunDescribe.described(newAssembly(FakeAgentSupport.propertiesFor('plain-round')),
                order(law(VerifyCheck.VerifyIn.FRESH_BOX)), new DescribeMedium.Host(tempDir), LawBinding.workingTree(tempDir))

        then:
        def failed = thrown(PipelineLoadFailedException)
        failed.message.contains('stages/build/stage.yaml')
        failed.message.contains('verify[0]')
        failed.message.contains("check 'lint' names program executor 'acme'")
        !Files.exists(StandIn.log(program))
    }
}
