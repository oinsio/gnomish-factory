package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR14, NFR-R2 of add-sandbox-base-image (design D22): a fresh container claim makes the first
 * stage's box live after the task branch exists and before the engine runs, so an operator
 * precondition refusal leaves the claim — for {@link ClaimedRunFailureDispatch} to turn into the
 * typed result — and any other failure is left to the first round. Split from
 * {@code TakeContainerFreshClaimSpec} for the file-size cap.
 */
class TakeContainerFreshClaimFirstBoxSpec extends Specification implements RunChainFakes {

    private final Tracker tracker = Mock()
    private final TaskBranchGit branches = Stub()
    private final TaskLifecycleStore repository = Mock()
    private final SandboxRunSupport support = Mock()

    def setup() {
        support.taskRepository() >> repository
        support.persistence() >> new InMemoryAttemptPersistence()
        support.workspace() >> new FakeWorkspace()
        support.pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        tracker.fetchTask(_) >> heldByUs()
        branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        branches.adopt(CLONE_DIR, 'PROJ-1', _) >> new BranchAdoption.AlreadyLocal('PROJ-1')
        branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
    }

    // Scenario: sandbox/execution-environment / A refused image releases the claimed task
    // Scenario: sandbox/execution-environment / Runtime unreachable at materialize returns the task
    def "a #kind refusal of the first box leaves the claim after the branch exists, and the engine never runs"() {
        given:
        def refusal = OperatorPreconditionRefusalSamples.of(kind)

        when:
        claim()

        then: 'the branch is created first: the seed clone of the first box reads it'
        1 * repository.createTask(*_)

        then: 'the first stage\'s box is made live, and its refusal propagates unchanged'
        1 * support.reattachFor('build') >> { throw refusal }
        def thrown = thrown(RuntimeException)
        thrown.is(refusal)

        and: 'the engine never ran'
        0 * tracker.finish(_, _)

        where:
        kind << OperatorPreconditionRefusalSamples.kinds()
    }

    def "a first-box failure that is no precondition is left to the first round, and the engine runs"() {
        when:
        def result = claim()

        then:
        1 * support.reattachFor('build') >> {
            throw new IllegalStateException('create network failed')
        }
        1 * tracker.finish(_, _)
        result instanceof TakeResult.Delivered
    }

    def "the first box is made live once, before the engine, for the task's first stage"() {
        when:
        def result = claim()

        then:
        1 * support.reattachFor('build')

        then:
        1 * tracker.finish(_, _)
        result instanceof TakeResult.Delivered
    }

    private TakeResult claim() {
        def git = new TaskGit(
                Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit(), new ClaimEpochBook())
        new TakeContainerFreshClaim(slotWiring(
                        assemblyRunning(new ScriptedExecutor([completedRound()])), git, tracker, CLONE,
                        containerTakeSupport())).claim(
                takeOrder(readyTask(), tracker, runOrder(completingPipeline())),
                [] as List<Segment>)
    }

    private ContainerTakeSupport containerTakeSupport() {
        ContainerSupportFactory factory = { cloneDir, taskId, segments, definition, creds ->
            support
        }
        new ContainerTakeSupport(new SandboxModeSelector(
                        new BindingProperties(null, [:]),
                        new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                        AdapterBindingRegistry.ratified([], BindingTrustTable.firstParty()),
                        { -> false } as ContainerRuntimeProbe),
                factory)
    }
}
