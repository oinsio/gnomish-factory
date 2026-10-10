package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-O1, NFR-R2 of make-checkpoint-gate-durable: the host medium's approval write — one
 * worktree commit that moves {@code state.json} past the gate and clears the park in
 * {@code task.json}, refused on what the tip shows. The bare-object twin is
 * {@link GitObjectsTaskRepositoryApproveCheckpointSpec}.
 */
class GitTaskRepositoryApproveCheckpointSpec extends Specification implements BareGitRepoFixture {

    private static final Position.AwaitingApproval GATE = new Position.AwaitingApproval('implement')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
    }

    /** The state a passing {@code manual} round leaves: at the gate, its passing round recorded. */
    private static TaskState gated(Position position = GATE) {
        new TaskState(position, 0, [
            new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [],
            ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none())
        ], ExecutorUsage.none())
    }

    /** What {@code TaskState.approveGate} yields for {@link #gated}: past the gate, history kept. */
    private static TaskState approved(Position next = new Position.AtStage('verify')) {
        def gate = gated()
        new TaskState(next, gate.attemptsUsed(), gate.attempts(), gate.totals())
    }

    private static final EscalationReport EARLIER = new EscalationReport.AttemptsExhausted(3)

    /** A task branch whose tip records {@code state} and a {@code paused} park owing a tracker write. */
    private void parkedAt(TaskState state) {
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), state)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(state, EARLIER), TrackerWrite.NONE)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(state, 'implement'), TrackerWrite.OWED)
    }

    private Path worktree() {
        registeredClone.worktrees().resolve('PROJ-1')
    }

    private TaskState tipState() {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        runner.run(worktree(), 'show', 'HEAD:.gnomish-task/state.json').stdout()))
    }

    private TaskRecord tipRecord() {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(
                        runner.run(worktree(), 'show', 'HEAD:.gnomish-task/task.json').stdout()))
    }

    private int commitCount() {
        runner.run(worktree(), 'rev-list', '--count', 'HEAD').stdout().forParsing().trim() as int
    }

    def "FR3: the approval is one commit moving state.json past the gate and clearing the park"() {
        given:
        parkedAt(gated())
        def before = commitCount()
        def logs = LogCaptureSupport.attach(CheckpointApprovalCheck)

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then: 'exactly one commit, labelled as the approval'
        commitCount() == before + 1
        runner.run(worktree(), 'log', '-1', '--format=%s').stdout().forParsing().trim() ==
                ServiceCommitMessages.taskEvent(TaskLifecycleEvent.APPROVED)

        and: 'state.json is the approved state: past the gate, the attempt history untouched'
        tipState() == approved()

        and: 'task.json: outcome cleared, marker false, the last escalation kept'
        def record = tipRecord()
        record.outcome() == null
        !record.trackerWritePending()
        record.lastEscalation() == EARLIER

        and: 'NFR-O1: one INFO line naming the task and the stage'
        def lines = logs.list
        lines.size() == 1
        lines[0].level == Level.INFO
        lines[0].formattedMessage.contains('PROJ-1')
        lines[0].formattedMessage.contains("'implement'")

        cleanup:
        logs.detach()
    }

    def "FR3: an approval of a tip at #tipPosition refuses, writes nothing and names the tip's position"() {
        given:
        parkedAt(gated(tipPosition))
        def before = commitCount()
        def recordBefore = tipRecord()
        def logs = LogCaptureSupport.attach(CheckpointApprovalCheck)

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then:
        def refused = thrown(CheckpointApprovalRefusedException)
        refused.actualPosition() == tipPosition

        and: 'NFR-R2: no commit, the park and the position as they were'
        commitCount() == before
        tipState().position() == tipPosition
        tipRecord() == recordBefore
        tipRecord().outcome() instanceof RecordedOutcome.Paused

        and: 'NFR-O1: one catalogued WARN naming the task'
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(OperatorEvent.CHECKPOINT_APPROVAL_REFUSED.head())
        logs.list[0].formattedMessage.contains('PROJ-1')

        cleanup:
        logs.detach()

        where:
        tipPosition << [
            new Position.AtStage('implement'),
            new Position.AwaitingApproval('verify'),
            new Position.PipelineEnd()
        ]
    }

    def "FR3: an approval whose state is itself at a gate refuses and writes nothing"() {
        given:
        parkedAt(gated())
        def before = commitCount()

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved(new Position.AwaitingApproval('verify')))

        then:
        def refused = thrown(CheckpointApprovalRefusedException)
        refused.actualPosition() == GATE
        commitCount() == before
    }

    def "NFR-R2: a repeated approval finds the tip it already moved and refuses"() {
        given:
        parkedAt(gated())
        repository.approveCheckpoint('PROJ-1', GATE, approved())
        def before = commitCount()

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then:
        thrown(CheckpointApprovalRefusedException)
        commitCount() == before
        tipState() == approved()
    }

    def "FR3: a tip without state.json fails the approval with git's own refusal"() {
        given: 'a branch whose tip lost its state file'
        parkedAt(gated())
        runner.run(worktree(), 'rm', '-q', '.gnomish-task/state.json')
        runner.run(worktree(), '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'drop state')

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then:
        def e = thrown(GitTaskRepositoryException)
        e.message.contains('state.json')
    }
}
