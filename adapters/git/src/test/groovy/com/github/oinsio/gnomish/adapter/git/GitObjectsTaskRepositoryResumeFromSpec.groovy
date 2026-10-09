package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.EgressCursorDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
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
 * FR7, FR8, NFR-O1, NFR-R2 of make-checkpoint-gate-durable: the bare-object resumed write — one
 * plumbing commit that replaces {@code state.json} with the reset and clears the park in
 * {@code task.json}, refused when the tip records no outcome. The host twin is
 * {@link GitTaskRepositoryResumeFromSpec}.
 */
class GitObjectsTaskRepositoryResumeFromSpec extends Specification implements BareGitRepoFixture {

    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    private static final String REF = 'refs/heads/gnomish/PROJ-1'

    private static final EscalationReport EXHAUSTED = new EscalationReport.AttemptsExhausted(1)

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

    /** The state an exhausted stage parks with: one burned quality failure on record. */
    private static TaskState exhausted() {
        TaskState.atStageStart('implement').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none()))
    }

    private void started() {
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(bareDir, 'refs/heads/base'), PIN, exhausted())
    }

    /** A task branch whose tip records an {@code escalated} park owing a tracker write. */
    private void parked() {
        started()
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(exhausted(), EXHAUSTED), TrackerWrite.OWED)
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

    def "FR7: the resumed write is one commit carrying the reset state and the cleared park"() {
        given:
        parked()
        def before = commitCount()
        def contextBefore = tipRecord().context()
        def logs = LogCaptureSupport.attach(ResumedWriteCheck)

        when:
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())

        then: 'exactly one commit, labelled as a resumed visit'
        commitCount() == before + 1
        gitOutput(bareDir, 'log', '-1', '--format=%s', REF) == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED)

        and: 'state.json is the reset handed in'
        tipState() == exhausted().resetAttempts()

        and: 'task.json: outcome cleared, marker false, the last escalation and the decisions kept'
        def record = tipRecord()
        record.outcome() == null
        !record.trackerWritePending()
        record.lastEscalation() == EXHAUSTED
        record.context() == contextBefore

        and: 'NFR-O1: one INFO line naming the task, the consumed outcome and the stage'
        logs.list.size() == 1
        logs.list[0].level == Level.INFO
        logs.list[0].formattedMessage.contains('PROJ-1')
        logs.list[0].formattedMessage.contains('Escalated')
        logs.list[0].formattedMessage.contains('implement')

        cleanup:
        logs.detach()
    }

    def "FR7: a resumed write over a tip with no recorded outcome refuses and writes nothing"() {
        given: 'a task mid-visit: started, nothing parked'
        started()
        def before = commitCount()
        def stateBefore = tipState()
        def logs = LogCaptureSupport.attach(ResumedWriteCheck)

        when:
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())

        then:
        def refused = thrown(ResumedWriteRefusedException)
        refused.message.contains('PROJ-1')

        and: 'no commit, the state as it was, and no line of its own (the caller decides)'
        commitCount() == before
        tipState() == stateBefore
        logs.list.empty

        cleanup:
        logs.detach()
    }

    def "NFR-R2: a repeated resumed write finds the tip it already moved and refuses"() {
        given:
        parked()
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())
        def before = commitCount()

        when:
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())

        then:
        thrown(ResumedWriteRefusedException)
        commitCount() == before
        tipState() == exhausted().resetAttempts()
    }

    // FR5 of fix-denial-attribution-durability: the resumed write reads no denial source, so the
    //     position the last sandboxed round committed rides into the regenerated state.json.
    def "FR7: the resumed write carries the tip's committed denial cursor forward"() {
        given:
        parked()
        def cursor = new EgressCursorDto('sha256:guard', '2026-09-05T10:00:00Z')
        editTip('round state') { Path dir ->
            Path stateJson = dir.resolve('state.json')
            def dto = StateJsonMapper.readDto(UntrustedText.branchDocument(Files.readString(stateJson)))
            Files.writeString(stateJson, TaskStateJson.mapper().writeValueAsString(
                            new StateJsonDto(dto.version(), dto.position(), dto.attemptsUsed(), dto.attempts(), dto.totals(), cursor)))
        }

        when:
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())

        then:
        tipStateDto().egressCursor() == cursor
        tipState() == exhausted().resetAttempts()
    }
}
