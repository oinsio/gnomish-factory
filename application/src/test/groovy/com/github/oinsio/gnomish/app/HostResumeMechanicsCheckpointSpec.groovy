package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException
import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR3, FR7 of make-checkpoint-gate-durable (design D2, D4, D6): the host medium's two
 * outcome-clearing writes behind {@link ResumeMechanics} — the approval of a gate and the resumed
 * write — each land through the worktree-rooted lifecycle repository as exactly one call, with the
 * gate read off the worktree's tip and the approved state derived from the order's pinned
 * definition. The container twin is {@code ContainerResumeMechanicsCheckpointSpec}.
 */
class HostResumeMechanicsCheckpointSpec extends Specification implements RunChainFakes {

    static final Path WORKTREE = Path.of('/tmp/gnomish-worktrees/PROJ-1')

    TaskLifecycleStore repository = Mock(TaskLifecycleStore)
    TaskStoreGit store = Stub(TaskStoreGit)

    /** What the worktree's state.json read answers; reassigned per scenario. */
    TaskState recorded = CheckpointMechanicsFixtures.gatedAt('build')

    def setup() {
        store.taskRepository(CLONE) >> repository
        store.readRecordedState(WORKTREE) >> { Optional.of(recorded) }
    }

    private HostResumeMechanics mechanics() {
        def git = new TaskGit(store, Stub(TaskBranchGit), Stub(TaskWorktreeGit), UnaryOperator.identity(),
                resumingBaseRefGit(), new ClaimEpochBook())
        def runner = new TakeResumeRunner(slotWiring(Stub(RunAssembly), git, Stub(Tracker)))
        // The mechanics' own bound pipeline is deliberately NOT the gated one: the approval must
        // resolve the gate against the order's pinned definition.
        new HostResumeMechanics(runner, git, CLONE, pipeline())
    }

    private TakeOrder order(PipelineDefinition definition = CheckpointMechanicsFixtures.gatedPipeline()) {
        takeOrder(heldByUs(), Stub(Tracker), runOrder(definition))
    }

    private static ResumeBootstrap branch() {
        new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(), new RecordedOutcome.Paused('build'),
                null, WORKTREE, 'gnomish/PROJ-1', 'abc123', false, BasePin.UNPINNED)
    }

    // FR3: the approval opens the gate the tip records — one repository write naming that gate, the
    //      state past it computed through approveGate against the pinned definition — and hands the
    //      continuation the approved state
    def "FR3: approveCheckpoint writes the approval once and returns the approved state"() {
        when:
        def approved = mechanics().approveCheckpoint(order(), branch())

        then: 'one approval names the gate read off the tip and the state past it'
        1 * repository.approveCheckpoint('PROJ-1', new Position.AwaitingApproval('build'),
                recorded.approveGate(CheckpointMechanicsFixtures.gatedPipeline()))
        0 * repository._

        and: 'the continuation runs from the next stage, attempt history untouched'
        approved.position() == new Position.AtStage('test')
        approved.attempts() == recorded.attempts()
    }

    // FR3, NFR-R2: a refusal decided on the tip propagates unchanged — the repository already
    //      reported it once (one failure, one log)
    def "FR3: a refused approval propagates to the caller"() {
        given:
        def refusal = new CheckpointApprovalRefusedException('PROJ-1', new Position.AwaitingApproval('build'),
                new Position.AtStage('test'))
        repository.approveCheckpoint(*_) >> { throw refusal }

        when:
        mechanics().approveCheckpoint(order(), branch())

        then:
        def thrown = thrown(CheckpointApprovalRefusedException)
        thrown.is(refusal)
    }

    // FR3: a tip that is not at a gate has nothing to approve — refused before any write
    def "FR3: a tip that is not at a gate writes nothing"() {
        given:
        recorded = TaskState.atStageStart('test')

        when:
        mechanics().approveCheckpoint(order(), branch())

        then:
        thrown(IllegalStateException)
        0 * repository._
    }

    // FR7: the resumed write hands the reset state to the repository in one call
    def "FR7: resumeFrom writes the reset state once"() {
        given:
        def reset = TaskState.atStageStart('build')

        when:
        mechanics().resumeFrom(order(), branch(), reset)

        then:
        1 * repository.resumeFrom('PROJ-1', reset)
        0 * repository._
    }

    // FR7, NFR-R2: a refused resumed write (nothing to consume) propagates unchanged
    def "FR7: a refused resumed write propagates to the caller"() {
        given:
        def refusal = new ResumedWriteRefusedException('PROJ-1')
        repository.resumeFrom(*_) >> { throw refusal }

        when:
        mechanics().resumeFrom(order(), branch(), TaskState.atStageStart('build'))

        then:
        def thrown = thrown(ResumedWriteRefusedException)
        thrown.is(refusal)
    }
}
