package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException
import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.Segment
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR3, FR7 of make-checkpoint-gate-durable (design D2, D4, D6): the container medium's two
 * outcome-clearing writes behind {@link ResumeMechanics} land through the bare-object lifecycle
 * repository as exactly one call each, after the kept box is disposed and before anything is
 * materialized — no factory-side commit lands behind a surviving box's back (FR17 of
 * harden-task-branch-contract). The host twin is {@code HostResumeMechanicsCheckpointSpec}.
 */
class ContainerResumeMechanicsCheckpointSpec extends Specification implements RunChainFakes {

    TaskRepository repository = Mock(TaskRepository)
    SandboxRunSupport support = Mock(SandboxRunSupport)

    /** What the bare tip's state.json read answers; reassigned per scenario. */
    TaskState recorded = CheckpointMechanicsFixtures.gatedAt('build')

    private ContainerResumeMechanics mechanics() {
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit),
                UnaryOperator.identity(), resumingBaseRefGit(), new ClaimEpochBook())
        def runner = new TakeContainerResumeRunner(slotWiring(Stub(RunAssembly), git, Stub(Tracker)))
        // The mechanics' own bound pipeline is deliberately NOT the gated one: the approval must
        // resolve the gate against the order's pinned definition.
        new ContainerResumeMechanics(runner, [] as List<Segment>, pipeline())
    }

    private TakeOrder order(PipelineDefinition definition = CheckpointMechanicsFixtures.gatedPipeline()) {
        takeOrder(heldByUs(), Stub(Tracker), runOrder(definition))
    }

    private ContainerResumeBootstrap branch() {
        new ContainerResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(),
                new RecordedOutcome.Paused('build'), null, support, 'gnomish/PROJ-1', 'abc123', false,
                BasePin.UNPINNED)
    }

    // FR3: the gate read off the bare tip is opened by one approval write, after the kept box is
    //      disposed and with no environment materialized; the approved state goes back to the caller
    def "FR3: approveCheckpoint disposes the kept box, then writes the approval once"() {
        when:
        def approved = mechanics().approveCheckpoint(order(), branch())

        then: 'the tip is read first (the repository accessor alone writes nothing)'
        1 * support.readFinalState() >> recorded
        1 * support.taskRepository() >> repository

        then: 'the kept box is disposed before the factory-side commit'
        1 * support.disposeExistingEnvironment()

        then: 'one approval names the gate read off the tip and the state past it'
        1 * repository.approveCheckpoint('PROJ-1', new Position.AwaitingApproval('build'),
                recorded.approveGate(CheckpointMechanicsFixtures.gatedPipeline()))
        0 * repository._
        0 * support._

        and: 'the continuation runs from the next stage, attempt history untouched'
        approved.position() == new Position.AtStage('test')
        approved.attempts() == recorded.attempts()
    }

    // FR3, NFR-R2: a refusal decided on the tip propagates unchanged
    def "FR3: a refused approval propagates to the caller"() {
        given:
        def refusal = new CheckpointApprovalRefusedException('PROJ-1', new Position.AwaitingApproval('build'),
                new Position.AtStage('test'))
        support.readFinalState() >> recorded
        support.taskRepository() >> repository
        repository.approveCheckpoint(*_) >> { throw refusal }

        when:
        mechanics().approveCheckpoint(order(), branch())

        then:
        def thrown = thrown(CheckpointApprovalRefusedException)
        thrown.is(refusal)
    }

    // FR3: a tip that is not at a gate has nothing to approve — refused before the box is touched
    def "FR3: a tip that is not at a gate disposes nothing and writes nothing"() {
        given:
        support.readFinalState() >> TaskState.atStageStart('test')

        when:
        mechanics().approveCheckpoint(order(), branch())

        then:
        thrown(IllegalStateException)
        0 * support.disposeExistingEnvironment()
        0 * repository._
    }

    // FR7: the resumed write hands the reset state to the bare-object repository in one call, after
    //      the kept box is disposed
    def "FR7: resumeFrom disposes the kept box, then writes the reset state once"() {
        given:
        def reset = TaskState.atStageStart('build')

        when:
        mechanics().resumeFrom(order(), branch(), reset)

        then:
        1 * support.disposeExistingEnvironment()

        then:
        1 * support.taskRepository() >> repository
        1 * repository.resumeFrom('PROJ-1', reset)
        0 * repository._
        0 * support._
    }

    // FR7, NFR-R2: a refused resumed write (nothing to consume) propagates unchanged
    def "FR7: a refused resumed write propagates to the caller"() {
        given:
        def refusal = new ResumedWriteRefusedException('PROJ-1')
        support.taskRepository() >> repository
        repository.resumeFrom(*_) >> { throw refusal }

        when:
        mechanics().resumeFrom(order(), branch(), TaskState.atStageStart('build'))

        then:
        def thrown = thrown(ResumedWriteRefusedException)
        thrown.is(refusal)
    }
}
