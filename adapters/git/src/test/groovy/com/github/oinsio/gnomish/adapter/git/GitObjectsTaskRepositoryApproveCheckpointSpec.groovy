package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-O1, NFR-R2 of make-checkpoint-gate-durable: the bare-object approval write — one
 * plumbing commit that moves {@code state.json} past the gate and clears the park in
 * {@code task.json}, refused on what the tip shows. The host twin is
 * {@link GitTaskRepositoryApproveCheckpointSpec}.
 */
class GitObjectsTaskRepositoryApproveCheckpointSpec extends Specification implements BareGitRepoFixture {

    private static final Position.AwaitingApproval GATE = new Position.AwaitingApproval('implement')

    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    private static final String REF = 'refs/heads/gnomish/PROJ-1'

    private static final EscalationReport EARLIER = new EscalationReport.AttemptsExhausted(3)

    @TempDir
    Path tempDir

    Path bareDir
    GitObjectsTaskRepository repository

    def setup() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        repository = new GitObjectsTaskRepository(
                GitObjects.open(bareDir, Files.createDirectories(tempDir.resolve('index'))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                DenialCursorSource.NONE)
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

    /** A task branch whose tip records {@code state} and a {@code paused} park owing a tracker write. */
    private void parkedAt(TaskState state) {
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(bareDir, 'refs/heads/base'), PIN, state)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(state, EARLIER), TrackerWrite.NONE)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(state, 'implement'), TrackerWrite.OWED)
    }

    private TaskState tipState() {
        StateJsonMapper.fromDto(tipStateDto())
    }

    private StateJsonDto tipStateDto() {
        StateJsonMapper.readDto(UntrustedText.branchDocument(gitOutput(bareDir, 'show', "${REF}:.gnomish-task/state.json")))
    }

    private TaskRecord tipRecord() {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(
                        UntrustedText.branchDocument(gitOutput(bareDir, 'show', "${REF}:.gnomish-task/task.json"))))
    }

    private int commitCount() {
        gitOutput(bareDir, 'rev-list', '--count', REF) as int
    }

    /** Rewrites the tip's envelope through a throwaway clone, as a sandboxed round's commit would. */
    private void editTip(String message, Closure<?> edit) {
        Path work = tempDir.resolve('edit-' + System.nanoTime())
        seedClone(tempDir, bareDir.toString(), work)
        gitOutput(work, 'checkout', '-B', 'gnomish/PROJ-1', 'origin/gnomish/PROJ-1')
        edit.call(work.resolve('.gnomish-task'))
        commitAll(work, message)
        gitOutput(work, 'push', 'origin', 'HEAD:' + REF)
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
        gitOutput(bareDir, 'log', '-1', '--format=%s', REF) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.APPROVED)

        and: 'state.json is the approved state: past the gate, the attempt history untouched'
        tipState() == approved()

        and: 'task.json: outcome cleared, marker false, the last escalation kept'
        def record = tipRecord()
        record.outcome() == null
        !record.trackerWritePending()
        record.lastEscalation() == EARLIER

        and: 'NFR-O1: one INFO line naming the task and the stage'
        logs.list.size() == 1
        logs.list[0].level == Level.INFO
        logs.list[0].formattedMessage.contains('PROJ-1')
        logs.list[0].formattedMessage.contains("'implement'")

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

    // FR5 of fix-denial-attribution-durability: the approval reads no denial source, so the
    //     position the last sandboxed round committed rides into the regenerated state.json.
    def "FR3: the approval carries the tip's committed denial cursor forward"() {
        given:
        parkedAt(gated())
        def cursor = new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z')
        editTip('round state') { Path dir ->
            Path stateJson = dir.resolve('state.json')
            def dto = StateJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(stateJson)))
            Files.writeString(stateJson, TaskStateJson.mapper().writeValueAsString(
                            new StateJsonDto(dto.version(), dto.position(), dto.attemptsUsed(), dto.attempts(), dto.totals(), cursor)))
        }

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then:
        tipStateDto().egressCursor() == cursor
    }

    def "FR3: a tip without state.json fails the approval rather than deciding on nothing"() {
        given:
        parkedAt(gated())
        editTip('drop state') { Path dir ->
            Files.delete(dir.resolve('state.json'))
        }
        def before = commitCount()

        when:
        repository.approveCheckpoint('PROJ-1', GATE, approved())

        then:
        def e = thrown(GitTaskRepositoryException)
        e.message.contains('state.json')
        commitCount() == before
    }
}
