package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, FR8, NFR-O1, NFR-R2 of make-checkpoint-gate-durable: the host medium's resumed write — one
 * worktree commit that replaces {@code state.json} with the reset and clears the park in
 * {@code task.json}, refused when the tip records no outcome. The bare-object twin is
 * {@link GitObjectsTaskRepositoryResumeFromSpec}.
 */
class GitTaskRepositoryResumeFromSpec extends Specification implements BareGitRepoFixture {

    private static final EscalationReport EXHAUSTED = new EscalationReport.AttemptsExhausted(1)

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
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE)
    }

    /** The state an exhausted stage parks with: one burned quality failure on record. */
    private static TaskState exhausted() {
        TaskState.atStageStart('implement').recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none()))
    }

    private void started() {
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), exhausted())
    }

    /** A task branch whose tip records an {@code escalated} park owing a tracker write. */
    private void parked() {
        started()
        repository.recordOutcome('PROJ-1', new TaskOutcome.Escalated(exhausted(), EXHAUSTED), TrackerWrite.OWED)
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
        runner.run(worktree(), 'log', '-1', '--format=%s').stdout().forParsing().trim() ==
                ServiceCommitMessages.taskEvent(TaskLifecycleEvent.RESUMED)

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

    def "FR7: a paused park is consumed the same way, whatever the outcome's kind"() {
        given:
        started()
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(exhausted(), 'implement'), TrackerWrite.NONE)
        assert tipRecord().outcome() instanceof RecordedOutcome.Paused

        when:
        repository.resumeFrom('PROJ-1', exhausted().resetAttempts())

        then:
        tipRecord().outcome() == null
        tipState() == exhausted().resetAttempts()
    }
}
