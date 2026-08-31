package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Task 5.4 of add-serve-sandbox-lifecycle (FR1): batch {@code take} carries container
 * dispositions through to its outcomes and aggregate exit code exactly as explicit mode does —
 * {@link TakeDispatcher#runBatch} threads the same {@code containerTakeSupport} record component
 * {@link TakeDispatcher#runExplicit} and {@link TakeDispatcher#runOneRef} already use (task
 * 5.1/5.2), so this proves the wiring end to end through the real {@link TakeRefDispatch} routing
 * rather than re-asserting the container fresh-claim mechanics {@code TakeContainerFreshClaimSpec}
 * already covers.
 *
 * <p>A single-slot ledger (serial) keeps this a pure wiring proof: concurrent-slot isolation is
 * {@code TakeSlotRunnerContainerConcurrencySpec}'s job (task 5.3), over real Docker.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle.
 */
@Timeout(10)
class TakeRefDispatchContainerBatchSpec extends Specification implements RunChainFakes {

    private static final TrackerConfig TRACKER_CONFIG = new TrackerConfig('github', 3)
    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    1, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14, null)

    Tracker tracker = Mock(Tracker)
    TrackerAdapterFactory factory = Stub(TrackerAdapterFactory)

    private static SandboxBindingProvider containerProvider() {
        new SandboxBindingProvider() {

                    @Override
                    String configName() {
                        BindingNames.CONTAINER
                    }

                    @Override
                    CapabilityPassport passport() {
                        CapabilityPassport.container()
                    }
                }
    }

    private SandboxRunSupport stubSupport(TaskRepository repository) {
        Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> ({} as Workspace)
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
    }

    /** A container-mode {@link ContainerTakeSupport}: CONTAINER is the resolved default binding,
     * and the factory hands back one stub {@link SandboxRunSupport} per task id. */
    private ContainerTakeSupport containerTakeSupport(Map<String, TaskRepository> repositories) {
        def registry = AdapterBindingRegistry.ratified([containerProvider()], BindingTrustTable.firstParty())
        def bindings = new BindingProperties(null, [:])
        def sandbox = new SandboxProperties('an-image', null, null, null, [], [], false, null, null, null, null)
        ContainerSupportFactory containerSupport = { Path clone, String id, List<Segment> segments, SandboxProperties sandboxProps, FactoryProperties factoryProps, PipelineDefinition definition, List<String> creds ->
            stubSupport(repositories[id])
        }
        new ContainerTakeSupport(testProperties(), bindings, sandbox, registry, {
            true
        }, containerSupport)
    }

    private TakeDispatcher dispatcher(Map<String, TaskRepository> repositories) {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.NotFound()
            classifyShape(_, _) >> new BranchShape.Bare()
        }, Stub(TaskWorktreeGit))
        new TakeDispatcher(git, WORKTREES_ROOT, 'taskId', testProperties(), FIXED_CLOCK,
                ['github': Stub(TrackerAdapterFactory)], MapSecretsProvider.NONE, TakeoverConfirmation.UNAVAILABLE,
                containerTakeSupport(repositories), new ClaimEpochBook())
    }

    private void dispatch(List<String> refs, Map<String, TaskRepository> repositories) {
        def heartbeat = TakeHeartbeat.forRun(tracker, TRACKER_CONFIG, { Duration d -> } as Sleeper)
        TakeRefDispatch.run(dispatcher(repositories),
                new TakeArguments(CLONE_DIR, refs, RunArguments.InteractiveMode.NONE, null, false, false),
                completingPipeline(), TRACKER_CONFIG, tracker, INSTANCE, [], factory,
                assemblyRunning(new ScriptedExecutor([
                    completedRound('PROJ-1'),
                    completedRound('PROJ-2')
                ])),
                heartbeat, SERVE_PROPERTIES, LoggerFactory.getLogger(TakeRefDispatchContainerBatchSpec))
    }

    // FR1: two fresh Ready tasks, both bound to the container adapter (the default), reach the
    // container fresh-claim path — same as explicit mode already proves per ref — via runBatch's
    // scheduler: both are created over their own container task repository, never a host one,
    // proving the single containerTakeSupport record component TakeDispatcher's other dispatch
    // methods already thread through is honored uniformly for a batch's every ref too.
    def "batch take routes every ref through the container fresh-claim path, not just one"() {
        given:
        def repoOne = Mock(TaskRepository)
        def repoTwo = Mock(TaskRepository)

        when:
        dispatch(['PROJ-1', 'PROJ-2'], ['PROJ-1': repoOne, 'PROJ-2': repoTwo])

        then:
        1 * tracker.fetchTask(new TaskRef('PROJ-1')) >> readyTask('PROJ-1')
        1 * tracker.fetchTask(new TaskRef('PROJ-2')) >> readyTask('PROJ-2')
        2 * tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        1 * repoOne.createTask({ it.taskId() == 'PROJ-1' }, 'HEAD', _)
        1 * repoTwo.createTask({ it.taskId() == 'PROJ-2' }, 'HEAD', _)

        and:
        thrown(TakeExitCodeException)
    }
}
