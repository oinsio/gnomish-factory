package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.HostVerificationKill.*

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR19 of migrate-verify-to-executor-contract (design D22, kill window W0 of D21 on the clone that
 * wrote the snapshot), the {@code run --resume} path: a git-mode host round killed after its
 * snapshot, during its verification, is resumed by a second instance — a new runner over the same
 * clone and its real bare origin — through {@link GitResumeContinuation#resumeFromRecordedPosition},
 * which hands the no-salvage owner's answer to the run. No stage executor runs (the fake agent's
 * argv log stays empty), no attempt is burned, nothing is salvaged, the killed checks' residue is
 * discarded and the round's check passes on the snapshot's tree; a second pickup changes nothing.
 * The take path's own features are {@code HostInterruptedVerificationTakeSpec}.
 */
class HostInterruptedVerificationRunResumeSpec extends GitResumeSpecBase {

    static final String TASK = 'PROJ-7'

    Path argv

    FactoryProperties agent

    def setup() {
        addConvergedOrigin(cloneDir, tempDir)
        argv = tempDir.resolve('plain-round.log')
        agent = FakeAgentSupport.propertiesCapturingArgv('plain-round', argv)
        repository().createTask(context(TASK), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('build'))
    }

    /** A second instance: a fresh assembly and runner over the same clone, its agent recording each round. */
    private void resumeAsSecondInstance(boolean discardWork = false) {
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), System.out, agent)
        try {
            new GitResumeRunner(assembly, taskGit, registeredClone, 'taskId')
                    .run(new RunOrder(cloneDir, null, pipeline('build'), discardWork), TASK, null)
        } catch (RunParkedException parked) {
            throw new AssertionError("the resumed round did not pass on the snapshot: ${parked.message}", parked)
        }
    }

    private int agentRounds() {
        Files.exists(argv) ? FakeAgentSupport.capturedInvocations(argv).size() : 0
    }

    private List<String> subjects() {
        gitOutput(cloneDir, 'log', '--format=%s', "gnomish/${TASK}").readLines()
    }

    // Scenario: git/git-task-persistence / A resumed host verification runs no agent
    // Scenario: git/git-task-persistence / Host residue above a snapshot is discarded
    def "FR19: a host run killed after its snapshot is re-verified on the snapshot by a second instance, #flag"() {
        given: 'the dying instance registered the round\'s snapshot, and its check left residue'
        killAfterSnapshot(taskGit, expectedWorktree(TASK), TASK, 'build', 0)
        def snapshot = gitOutput(cloneDir, 'rev-parse', registerRef(TASK))

        when:
        resumeAsSecondInstance(discardWork)

        then: 'no stage executor ran and nothing was salvaged'
        agentRounds() == 0
        !subjects().contains('gnomish: salvage')

        and: 'the round closed on its snapshot, and its check — which fails on the residue — passed'
        def round = closeAbove(cloneDir, TASK, snapshot)
        subjectOf(cloneDir, round).startsWith('gnomish: round build#0')
        def state = stateAt(cloneDir, round)
        state.position() == new Position.PipelineEnd()
        state.attempts()*.result() == [AttemptRecord.Result.PASSED]

        and: 'no attempt was burned'
        state.attemptsUsed() == 0

        where:
        flag | discardWork
        'by default' | false
        'under --discard-work' | true
    }

    // Scenario: lifecycle/task-branch-contract / An interrupted verification is resumed by its one owner
    def "FR19: a second pickup after the round's closing commit changes nothing"() {
        given:
        killAfterSnapshot(taskGit, expectedWorktree(TASK), TASK, 'build', 0)
        resumeAsSecondInstance()
        def closed = gitOutput(cloneDir, 'rev-parse', registerRef(TASK))
        def history = subjects()

        when:
        resumeAsSecondInstance()

        then: 'the delivered task is only reported: no round, no commit, no salvage'
        agentRounds() == 0
        gitOutput(cloneDir, 'rev-parse', registerRef(TASK)) == closed
        subjects() == history
    }
}
