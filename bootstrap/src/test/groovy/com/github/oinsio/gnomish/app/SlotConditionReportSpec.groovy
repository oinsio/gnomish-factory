package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.DaemonCondition
import com.github.oinsio.gnomish.app.serve.DaemonConditions
import com.github.oinsio.gnomish.app.serve.DirtyNotifier
import com.github.oinsio.gnomish.app.serve.PreconditionRecheck
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates
import com.github.oinsio.gnomish.app.serve.RemoteOutageLedgerSink
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
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
 * FR15 of add-sandbox-base-image (design D18, D19): a slot that ends on a typed operator
 * precondition refusal reports it to the daemon's conditions after its sinks, so the condition of
 * that kind opens — the feed's one gate read then claims nothing, pinned once in {@code
 * DaemonConditionsSpec} — and a slot that ends any other way opens none. Driven over the real slot
 * runner, the real container support and a scripted docker, each refusal provoked where a running
 * daemon would meet it; without the report every feature here is red.
 */
class SlotConditionReportSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish-factory', 'sc0001')
    private static final Map<String, DaemonConditionKind> OPENED_BY_CODE = [
        (OperatorEvent.CONDITION_CLONE_REF_FORMAT_OPENED.code()) : DaemonConditionKind.CLONE_REF_FORMAT,
        (OperatorEvent.CONDITION_CONTAINER_RUNTIME_OPENED.code()) : DaemonConditionKind.CONTAINER_RUNTIME,
        (OperatorEvent.CONDITION_IMAGE_CONTRACT_OPENED.code()) : DaemonConditionKind.IMAGE_CONTRACT,
        (OperatorEvent.CONDITION_BOX_GIT_FLOOR_OPENED.code()) : DaemonConditionKind.BOX_GIT_FLOOR
    ]

    @TempDir
    Path tempDir

    def git = TaskGitFixture.real()
    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    def tracker = new InMemoryTracker()
    def runtimeAnswers = true
    Path cloneDir
    DaemonConditions conditions
    def logs

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        writeMinimalProject(cloneDir)
        commitAll(cloneDir, 'init')
        addConvergedOrigin(cloneDir, tempDir)
        new InMemoryTrackerHarness(tracker).seed(REF,
                new TaskSnapshot(REF.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        conditions = RemoteOutageGates.forServe(BaseRefGit.UNWIRED, cloneDir,
                new ServeProperties(0, null, null, null, null, null, null, null, null, null), new VirtualClock(),
                DirtyNotifier.NOOP, RemoteOutageLedgerSink.NONE, { kind ->
                    Optional.empty()
                } as PreconditionRecheck)
        logs = LogCaptureSupport.attach(DaemonCondition)
    }

    def cleanup() {
        logs?.detach()
    }

    // Scenario: serve/factory-serve / The container runtime stops under a running daemon
    def "FR15: the runtime stopping under the daemon opens the container-runtime condition"() {
        given:
        runtimeAnswers = false

        expect:
        slotOpens() == [
            DaemonConditionKind.CONTAINER_RUNTIME
        ] as Set
    }

    def "FR15: a slot's typed refusal opens the condition of its kind: #kind"() {
        given:
        provoke(kind)

        expect:
        slotOpens() == [kind] as Set

        where:
        kind << [
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.BOX_GIT_FLOOR,
            DaemonConditionKind.CLONE_REF_FORMAT
        ]
    }

    /** Breaks {@code kind} the way an operator's change under a running daemon would. */
    private void provoke(DaemonConditionKind kind) {
        switch (kind) {
            case DaemonConditionKind.IMAGE_CONTRACT:
                docker.imageConfig = '{"Volumes":null,"Labels":{}}'
                break
            case DaemonConditionKind.BOX_GIT_FLOOR:
                docker.boxGitVersion = 'git version 2.30.0'
                break
            default:
                gitOutput(cloneDir, 'refs', 'migrate', '--ref-format=files')
        }
    }

    // Scenario: serve/factory-serve / An unclassified failure opens no condition
    def "NFR-R2: a slot that dies on an unclassified failure opens no condition"() {
        given: 'the task vanishes from the tracker between its claim and the slot body'
        def vanishing = new InMemoryTracker() {
                    @Override
                    TrackerTask fetchTask(TaskRef ref) {
                        throw new IllegalStateException('tracker lost the task')
                    }
                }

        when:
        runner(vanishing).run(REF)

        then:
        !conditions.anyOpen()
        logs.list.findAll { it.level.isGreaterOrEqual(Level.WARN) }.isEmpty()
    }

    private Set<DaemonConditionKind> slotOpens() {
        tracker.claim(REF, INSTANCE.value())
        runner(tracker).run(REF)
        assert conditions.anyOpen()
        logs.list.findAll { it.level == Level.WARN }.collect { event ->
            OPENED_BY_CODE.find { code, kind ->
                event.formattedMessage.startsWith("[${code}] ")
            }?.value
        } as Set
    }

    private TakeSlotRunner runner(InMemoryTracker slotTracker) {
        new TakeSlotRunner(wiring(slotTracker), new RunOrder(cloneDir, null, pipeline(), false),
                slotTracker, INSTANCE, conditions)
    }

    private SlotWiring wiring(InMemoryTracker slotTracker) {
        def factory = { Path c, String t, List<Segment> s, d, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, sandbox, tempDir.resolve('guard')), s,
            SandboxLifecyclePass.NONE, git.epochs())
        } as ContainerSupportFactory
        def selector = new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), sandbox,
        AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty()),
        { -> runtimeAnswers } as ContainerRuntimeProbe)
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'),
                FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.create())
        new SlotWiring(assembly, git, RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir),
                'taskId', new AbortFuse(new AbortHandler(slotTracker, assembly.timeEquipment().clock()), 3), [],
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
}
