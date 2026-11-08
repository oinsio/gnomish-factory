package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.describe.DescribeOutcome
import com.github.oinsio.gnomish.app.describe.DescribePreflight
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.subprocess.Drain
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity of design D10's {@code Describe} row (NFR-S1, NFR-C1 of define-executor-contract;
 * ADR 0017), on a real box: the credential names the law grants a {@code program} executor, agreed
 * by its describe in the box through the real preflight, are exactly the names its child receives —
 * as its own resolved layer through the run allowlist's grant, composed by the box like every exec —
 * and a sibling {@code command} check in the same box receives none of them. The box is built by the
 * production {@link ContainerRunSupportFactory} over the law, so its guard also admits the law's
 * executor egress host (FR10).
 *
 * <p>The {@code program} round is a real {@link ProgramExecutor} round in the box (task 7.1): the
 * declared program is the box's shell, which reports the names its child received and the key's
 * value in its result, read back through the one result reader. The value source is a fixed map, the
 * stand-in for the root's secrets port the allowlist is built over.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ProgramCredentialDeliverySpec extends Specification implements BareGitRepoFixture {

    static final String VALUE = 'acme-delivered-value'

    static final ChildEnvValues SECRETS = { String name ->
        Optional.ofNullable([ACME_API_KEY: VALUE][name])
    } as ChildEnvValues

    @TempDir
    Path tempDir

    String taskId

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
            Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId).toFile().deleteDir()
        }
    }

    // Scenario: executor/result-channel / Declared credential reaches only its executor
    def "NFR-S1, NFR-C1: the names the law grants are the names the program child receives, and a sibling command check receives none"() {
        given: 'a law granting acme ACME_API_KEY and one egress host, and the box built for it'
        def law = ProgramInBox.law()
        def installation = ChildEnvAllowlist.of([], [], SECRETS)
        def support = startTask(law, installation)
        def box = support.lease().environmentFor('work')

        when: 'the preflight describes acme in the box'
        def outcome = new DescribePreflight(new VirtualClock(), ProgramBounds.DEFAULTS, installation.programGrant())
                .describe(box, law)

        then:
        outcome instanceof DescribeOutcome.Described
        def granted = (outcome as DescribeOutcome.Described).executors().credentials().of('acme')
        granted == ['ACME_API_KEY']

        when: 'a real ProgramExecutor round runs acme in the box with its grant; a sibling exec carries nothing'
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, ProgramInBox.realTime(), installation.programGrant(),
                Files.createDirectories(tempDir.resolve('stderr')))
        def result = new ProgramExecutor(law.executors()[0], ProgramInBox.describe(), granted, box, equipment).start(ProgramInBox.request())
        def program = (result.reason().forLog().split(';')[0].split(',') as List).findAll {
            it
        } as Set
        def sibling = names(box, [:])

        then: 'the program child received exactly the granted names beyond what every child gets'
        result.status() == Status.FAILED
        (program - sibling - [
            'GNOMISH_RESULT_FILE',
            'GNOMISH_ANSWER_FILE'
        ]) == granted as Set
        !sibling.contains('ACME_API_KEY')

        and: 'with the value the source resolved'
        result.reason().forLog().split(';')[1] == VALUE

        when: 'a command check runs in the same box'
        def verdict = new ShellCommandCheckRunner(new VirtualClock())
                .withChildEnv(installation)
                .withEnvironments(support.pieces(null).checkEnvironments())
                .run(VerifyChecks.command('test -z "${ACME_API_KEY:-}"'), support.workspace())

        then: 'it passes only with the key absent from its process'
        verdict == new VerdictAnswer.Sarif([])

        and: 'FR10: the box guard admits the law\'s executor egress host beside the operator\'s'
        Files.readString(Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId, 'allowlist.json')) ==
                '["example.com","api.acme.example"]'
    }

    private ContainerRunSupport startTask(PipelineDefinition law, ChildEnvAllowlist installation) {
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, ['example.com'], [], false, null, null, null, null)
        def project = initWorkingRepo(tempDir, 'program-credential-project')
        Files.createDirectories(project.resolve('.gnomish'))
        Files.writeString(project.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(project)
        taskId = "CTN-GRANT-${System.nanoTime() % 100000}"
        def support = ContainerSupportFixture.directFor(project, taskId, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), law.stages())
        ], sandbox, ClaimEpochSource.NONE, installation, law)
        support.taskRepository().createTask(
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskStart.commit(project, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('work'))
        addRemote(project, 'origin', 'https://example.invalid/org/program-credential-project.git')
        support
    }

    /** The variable names one exec's child received, its factory-set layer {@code env}. */
    private static Set<String> names(TaskExecutionEnvironment box, Map<String, String> env) {
        output(box, [
            'sh',
            '-c',
            'env | cut -d= -f1'
        ], env).readLines().findAll {
            it
        } as Set
    }

    private static String output(TaskExecutionEnvironment box, List<String> argv, Map<String, String> env) {
        def handle = box.exec(new ExecCommand(argv, env, null, StderrPolicy.MERGED))
        def drain = Drain.capped(handle.output(), 'delivery-spec', 64 * 1024)
        assert handle.waitForExit() == 0
        Drain.joinAll(handle.postExitBound(), drain)
        drain.captured()
    }
}
