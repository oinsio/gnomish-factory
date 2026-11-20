package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-S2 of migrate-verify-to-executor-contract (design D18, "Where a refusal at run assembly
 * goes"; ADR 0019, decision 5): {@link RunDescribe}, the one owner of a run's described law, admits
 * the connections of the SCM built-ins the law's checks name before its branch decides, over the real
 * assembly. An unconnected one is a located error on the describe refusal's path — the same {@link
 * LawDescription.Refused} a program that cannot be described yields, joined with it — so a manual
 * {@code run} fails as an invalid law does; a law that names no SCM built-in is described, and its
 * checks bind, with neither subsection configured.
 */
class RunDescribeConnectionSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path tempDir

    private static final VerifyCheck.Executor STATUS = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR,
    'scm-status', 'ci', [:], Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
    VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, ['build'])

    private ManualRunAssembly assembly() {
        newAssembly(FakeAgentSupport.propertiesFor('plain-round'))
    }

    private RunOrder order(PipelineDefinition definition) {
        new RunOrder(tempDir, null, definition, false)
    }

    private static PipelineDefinition law(List<VerifyCheck> checks, String program = null) {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(program ? ExecutorType.PROGRAM : ExecutorType.AGENT_CLI, 'model-x', [:],
        Sandbox.none(), program ? 'acme' : null),
        'instructions.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, program ? [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program], [:], [], [])
        ] : [])
    }

    def "FR13: a law that names no SCM built-in is described, and its checks bind, with neither subsection configured"() {
        given:
        def files = VerifyChecks.builtin('files', [files: ['a']])
        def definition = law([
            files,
            VerifyChecks.command('true')
        ])

        when:
        def described = assembly().describe(order(definition), new DescribeMedium.Host(tempDir), [],
        LawBinding.workingTree(tempDir)) as DescribedLaw
        def checks = ExecutorAdapterSelector.stageRounds(assembly(), TestChildEnvironment.hostAllowlist(),
                PipelineLaw.ofContent([:]), described, definition, HttpRunContext.none(),
                RunLaw.open(LawBinding.workingTree(tempDir)).pinCheck()).checks()

        then:
        described.connectionOf('scm-status').isEmpty()
        described.connectionOf('github-actions').isEmpty()
        checks.bind(files, CheckRef.of(0, files), new DirectoryWorkspace(tempDir)) instanceof StageExecutor
    }

    def "FR13, NFR-S2: a law whose check names scm-status with no factory.check.scm-status is refused with a located error naming the check, the executor and the key"() {
        when:
        def described = assembly().describe(order(law([
            VerifyChecks.builtin('files', [files: ['a']]),
            STATUS
        ])),
        new DescribeMedium.Host(tempDir), [], LawBinding.workingTree(tempDir))

        then:
        def errors = (described as LawDescription.Refused).errors()
        errors.size() == 1
        errors[0].file() == 'stages/build/stage.yaml'
        errors[0].where() == 'verify[1]'
        errors[0].message().contains("check 'ci' names executor 'scm-status'")
        errors[0].message().contains('factory.check.scm-status.api-url is required')
    }

    def "FR13: the refusal joins the describe preflight's own located errors in one refusal"() {
        when:
        def described = assembly().describe(order(law([STATUS], tempDir.resolve('no-such-program').toString())),
        new DescribeMedium.Host(tempDir), [], LawBinding.workingTree(tempDir))

        then:
        def errors = (described as LawDescription.Refused).errors()
        errors.size() == 2
        errors[0].file() == 'config.yaml'
        errors[1].where() == 'verify[0]'
    }

    def "FR13: a run past the pipeline's end runs no check, so it admits no connection and is described"() {
        when:
        def described = assembly().describe(order(law([STATUS])), new DescribeMedium.NoStageRuns(), [],
        LawBinding.workingTree(tempDir))

        then:
        described instanceof DescribedLaw
        (described as DescribedLaw).connectionOf('scm-status').isEmpty()
    }

    def "FR13, UX1: on run the unconnected built-in is the located error exit of an invalid law"() {
        when:
        ManualRunDescribe.described(assembly(), order(law([STATUS])), new DescribeMedium.Host(tempDir),
        LawBinding.workingTree(tempDir))

        then:
        def failed = thrown(PipelineLoadFailedException)
        failed.message.contains('stages/build/stage.yaml')
        failed.message.contains("check 'ci' names executor 'scm-status'")
    }
}
