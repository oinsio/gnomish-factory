package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.RoundExecution
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-R2 of add-sandbox-base-image (design D22, the {@code OperatorPreconditionRefusals}
 * row): the flow, over the real container support and a scripted docker whose image carries no
 * contract label. A fresh {@code take} and a fresh {@code run} each meet the refusal at their first
 * box, before the engine, and end on exit 8; the round's catch-all never sees it, so no {@code
 * EXECUTOR_THREW} line is written and no task is parked. Without the early step the first round
 * materializes the box inside the engine and the run parks as cannot-execute.
 */
class FirstBoxPreconditionFlowSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish-factory', 'fb0001')
    private static final String UNLABELLED_IMAGE = '{"Volumes":null,"Labels":{}}'

    @TempDir
    Path tempDir

    // One bundle, so a support stamps from the tenure record the take's claim fills.
    def git = TaskGitFixture.real()
    def docker = new ScriptedSandboxDocker(imageConfig: UNLABELLED_IMAGE)
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        writeMinimalProject(cloneDir)
        commitAll(cloneDir, 'init')
        addConvergedOrigin(cloneDir, tempDir)
    }

    // Scenario: tracker/tracker-take / Operator precondition refused after the claim exits 8
    def "a fresh take refused at its first box ends on exit 8, released, with no executor line"() {
        given:
        def tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(REF,
                new TaskSnapshot(REF.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        def logs = LogCaptureSupport.attach(RoundExecution)

        when:
        def result = new TakeClaimAndWorkFactory(wiring(tracker)).forSlot().claimAndWork(
                new TakeOrder(new RunOrder(cloneDir, null, pipeline(), false), tracker.fetchTask(REF), tracker, INSTANCE))

        then: 'the typed result, named by the image contract, on the shared code 8'
        result instanceof TakeResult.OperatorPreconditionUnmet
        ((TakeResult.OperatorPreconditionUnmet) result).kind() == DaemonConditionKind.IMAGE_CONTRACT
        TakeExitCodeMapper.exitCodeFor(result) == 8

        and: 'released plain, the lease path returning it after its TTL (ADR 0002): no park, no abort marker'
        def after = tracker.fetchTask(REF)
        after.state() == new TrackerTaskState.Working(INSTANCE.value())
        after.abortFacts().count() == 0

        and: 'no agent round started, and the round never classified the refusal'
        !docker.starts.any { it.contains('stream-json') }
        !logs.list.any {
            it.level == Level.ERROR && it.formattedMessage.startsWith(OperatorEvent.EXECUTOR_THREW.head())
        }

        cleanup:
        logs?.detach()
    }

    // Scenario: cli/manual-run / A precondition refusal in run exits 8, never 1
    def "a fresh run refused at its first box exits 8 with no executor line"() {
        given:
        def logs = LogCaptureSupport.attach(RoundExecution)
        def factory = { Path c, String t, List<Segment> s, definition, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, sandbox, tempDir.resolve('guard')), s,
            SandboxLifecyclePass.NONE, git.epochs())
        } as ContainerSupportFactory
        def runner = new ContainerGitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round')),
                git, factory, LiveConsoleIO.onStdout())

        when:
        runner.run(new RunOrder(cloneDir, null, pipeline(), false), segments(), context('T-FRESH'),
                TaskState.atStageStart('build'))

        then:
        def refused = thrown(ImageContractRefusedException)
        new RunExitCodeMapper().getExitCode(refused) == 8

        and:
        !docker.starts.any { it.contains('stream-json') }
        !logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.EXECUTOR_THREW.head())
        }

        cleanup:
        logs?.detach()
    }

    private SlotWiring wiring(InMemoryTracker tracker) {
        def factory = { Path c, String t, List<Segment> s, d, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, sandbox, tempDir.resolve('guard')), s,
            SandboxLifecyclePass.NONE, git.epochs())
        } as ContainerSupportFactory
        def selector = new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), sandbox,
        AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty()),
        { true } as ContainerRuntimeProbe)
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), sink(),
                FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.create())
        new SlotWiring(assembly, git, RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir),
                'taskId', new AbortFuse(new AbortHandler(tracker, assembly.timeEquipment().clock()), 3), [],
                new ContainerTakeSupport(selector, factory), new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(cloneDir))))
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'purpose', [], [],
            new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
            'stages/build/instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), pipeline().stages())
        ]
    }

    private static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }
}
