package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.domain.engine.Position
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Identity spec of design D1, D2 and D7 of make-checkpoint-gate-durable (the {@code
 * TaskRepository.approveCheckpoint} row; {@code testing.md}, "Invariant specs across a flow"): the
 * component specs prove the Engine stops at a gate, the round commit writes it, the continuation
 * approves it and each repository lands one commit; only the flow over a bare origin proves those
 * links are joined — that no tip of the branch is past the gate without the approval behind it,
 * and none carries the approval while still at the gate.
 *
 * <p>Run on both lifecycle media ({@link ContinuationMedium}): the manual pass is a real run of the
 * pipeline with the fake agent, the approval is the real {@code run --resume} of the park. Every
 * commit is read off origin through the production wire readers ({@link BranchHistory}).
 *
 * <p>The approved position is checked against the pinned definition here, spelled from the stage
 * order alone ({@link BranchHistory#following}): the repositories no longer check it (design D2),
 * so without this spec a wrong "position after the gate" would land green.
 *
 * <p>FR1, FR3, FR4, M1 of make-checkpoint-gate-durable.
 */
class GateApprovalIdentitySpec extends Specification {

    private static final String TASK = 'GATE-1'

    @TempDir
    Path tempDir

    // FR1, FR3, FR4, M1: at a gate or past the approval, never both and never neither; past the gate
    //     iff the outcome is cleared, in the approval's one commit; at the position the pinned
    //     definition names after the gate
    def "FR3, FR4: after a manual pass and its approval, every tip on #medium is at the gate or carries the approval (#pipeline)"() {
        given: 'a manual pass of "build" parked at its gate, on origin'
        ContinuationMedium m = medium == 'host'
                ? new HostContinuationMedium(tempDir.resolve('host'))
                : new ContainerContinuationMedium(tempDir.resolve('container'))
        def definition = pipeline == 'two gates' ? ParkPipelines.pausing() : ParkPipelines.lastGate()
        m.park(TASK, definition, 'plain-round')
        def history = new BranchHistory(m.origin(), TASK)
        def gate = new Position.AwaitingApproval('build')

        when: 'the operator resumes the task: the approval, then the continued drive'
        m.resume(TASK, definition, 'plain-round', null)
        def walk = history.commits().dropWhile { history.position(it) != gate }
        def approvals = walk.findAll {
            history.position(it) != gate && history.position(history.parent(it)) == gate
        }

        then: 'the pass wrote the gate, and exactly one commit leaves it'
        !walk.isEmpty()
        approvals.size() == 1
        String approval = approvals.first()
        String parked = history.parent(approval)

        and: 'every tip from the gate on carries the gate or descends from the approval — exactly one of the two'
        walk.every {
            (history.position(it) == gate) != history.descends(it, approval)
        }

        and: 'across the approval, the position is past the gate iff the outcome is null'
        [parked, approval].every {
            (history.position(it) != gate) == (history.outcome(it) == null)
        }
        history.outcome(parked) instanceof TaskOutcomeDto.Paused
        history.subject(approval) == 'gnomish: task approved'

        and: 'the approved position is the one following the gate in the pinned definition'
        history.position(approval) == BranchHistory.following(definition, 'build')

        and: 'the checkpoint reset nothing'
        history.state(approval).attempts() == history.state(parked).attempts()
        history.state(approval).attemptsUsed() == history.state(parked).attemptsUsed()

        where:
        medium | pipeline
        'host' | 'two gates'
        'container' | 'two gates'
        'host' | 'last stage a gate'
        'container' | 'last stage a gate'
    }
}
