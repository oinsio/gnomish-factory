package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException
import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1, NFR-O1, UX3 of fix-lifecycle-push: the failure side of the {@code TaskRepository}
 * decorator. A push that fails, or a clone with no origin at all, never disturbs the lifecycle write
 * that already succeeded; a lifecycle write that itself fails is not followed by a push. The
 * success side is the sibling {@link PushBestEffortTaskRepositorySpec}.
 */
class PushBestEffortTaskRepositoryFailureSpec extends Specification implements LifecyclePushFixture {

    @TempDir
    Path tempDir

    def setup() {
        initLifecyclePushFixture()
    }

    private TaskRepository decorated(TaskRepository delegate, Path clone = cloneDir) {
        new PushBestEffortTaskRepository(delegate, git, clone)
    }

    def "an unreachable origin logs one WARN naming task, branch and event, and never propagates"() {
        given:
        gitOutput(cloneDir, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)

        when:
        def events = capture {
            repository.appendDecision(TASK_ID, new Decision('go', null, null, Instant.EPOCH), TaskState.atStageStart('work'))
        }

        then:
        1 * delegate.appendDecision(TASK_ID, _, _) >> {
            commitOnTaskBranch('resumed')
        }
        noExceptionThrown()

        and:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith(OperatorEvent.LIFECYCLE_PUSH_FAILED.head() + 'lifecycle push failed:')
        events[0].formattedMessage.contains("taskId=${TASK_ID}")
        events[0].formattedMessage.contains("branch=${BRANCH}")
        events[0].formattedMessage.contains('event=RESUMED')
    }

    // NFR-O1: the WARN names the lifecycle event, and every terminal outcome maps to its own — the
    // operator reading a lost push needs to know WHICH write failed to replicate, and a table over
    // all four is what makes the mapping (not just one arm of it) observable.
    def "each terminal outcome's WARN names its own lifecycle event"() {
        given:
        gitOutput(cloneDir, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)

        when:
        def events = capture {
            repository.recordOutcome(TASK_ID, outcome, TrackerWrite.OWED)
        }

        then:
        1 * delegate.recordOutcome(TASK_ID, _, TrackerWrite.OWED) >> {
            commitOnTaskBranch(event.toLowerCase())
        }
        events.size() == 1
        events[0].formattedMessage.contains("event=${event}")

        where:
        event | outcome
        'COMPLETED' | new TaskOutcome.Completed(TaskState.atStageStart('work'))
        'PAUSED' | new TaskOutcome.Paused(TaskState.atStageStart('work'), 'work')
        'ESCALATED' | new TaskOutcome.Escalated(TaskState.atStageStart('work'),
                new EscalationReport.AttemptsExhausted(3))
        'ABORTED' | new TaskOutcome.Aborted(TaskState.atStageStart('work'),
                new AttemptKey('T-1', 'work', 0), UntrustedText.subprocess('violation'))
    }

    // FR3, NFR-O1 of make-checkpoint-gate-durable: a lost approval push names the approval event.
    def "a failed approval push names the APPROVED event in its single WARN"() {
        given:
        gitOutput(cloneDir, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)

        when:
        def events = capture {
            repository.approveCheckpoint(TASK_ID, new Position.AwaitingApproval('work'), TaskState.atStageStart('next'))
        }

        then:
        1 * delegate.approveCheckpoint(TASK_ID, _, _) >> {
            commitOnTaskBranch('approved')
        }
        events.size() == 1
        events[0].formattedMessage.contains('event=APPROVED')
    }

    // NFR-R2 of make-checkpoint-gate-durable: a refused approval wrote nothing, so nothing is pushed.
    def "a refused approval propagates and pushes nothing"() {
        given:
        commitOnTaskBranch('pre-existing')
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        def gate = new Position.AwaitingApproval('work')

        when:
        repository.approveCheckpoint(TASK_ID, gate, TaskState.atStageStart('next'))

        then:
        1 * delegate.approveCheckpoint(TASK_ID, gate, _) >> {
            throw new CheckpointApprovalRefusedException(TASK_ID, gate, new Position.PipelineEnd())
        }
        thrown(CheckpointApprovalRefusedException)
        remoteTip() == Optional.empty()
    }

    // FR7, NFR-R2 of make-checkpoint-gate-durable: a refused resumed write wrote nothing, so
    //     nothing is pushed.
    def "a refused resumed write propagates and pushes nothing"() {
        given:
        commitOnTaskBranch('pre-existing')
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)

        when:
        repository.resumeFrom(TASK_ID, TaskState.atStageStart('work'))

        then:
        1 * delegate.resumeFrom(TASK_ID, _) >> {
            throw new ResumedWriteRefusedException(TASK_ID)
        }
        thrown(ResumedWriteRefusedException)
        remoteTip() == Optional.empty()
    }

    def "a clone with no origin attempts no push and stays silent"() {
        given:
        def local = initWorkingRepo(tempDir, 'local')
        Files.writeString(local.resolve('a.txt'), 'a')
        commitAll(local, 'init')
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate, local)

        when:
        def events = capture {
            repository.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('work'))
        }

        then:
        1 * delegate.createTask(_, _, _, _)
        noExceptionThrown()
        events.isEmpty()
    }

    def "a failed lifecycle write propagates and pushes nothing"() {
        given:
        commitOnTaskBranch('pre-existing')
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)

        when:
        repository.recordOutcome(TASK_ID, new TaskOutcome.Aborted(TaskState.atStageStart('work'),
                new AttemptKey(TASK_ID, 'work', 0), UntrustedText.subprocess('violation')), TrackerWrite.OWED)

        then:
        1 * delegate.recordOutcome(TASK_ID, _, TrackerWrite.OWED) >> {
            throw new IllegalStateException('boom')
        }
        thrown(IllegalStateException)
        remoteTip() == Optional.empty()
    }
}
