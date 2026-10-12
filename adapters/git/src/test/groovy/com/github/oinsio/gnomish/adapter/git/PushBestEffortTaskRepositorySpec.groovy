package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2 of fix-lifecycle-push: every lifecycle write a task records is followed by one
 * best-effort push of the task branch, before the call returns to its caller — so a caller that
 * signals a tracker write next does so after the replication attempt.
 *
 * <p>What the decorator does when the push, or the write beneath it, FAILS is the sibling {@link
 * PushBestEffortTaskRepositoryFailureSpec}; both share {@link LifecyclePushFixture}.
 */
class PushBestEffortTaskRepositorySpec extends Specification implements LifecyclePushFixture {

    @TempDir
    Path tempDir

    def setup() {
        initLifecyclePushFixture()
    }

    private TaskRepository decorated(TaskRepository delegate) {
        new PushBestEffortTaskRepository(delegate, git, cloneDir, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())
    }

    def "createTask's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('work'))

        then:
        1 * delegate.createTask(_, TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), _) >> {
            recorded = commitOnTaskBranch('started')
        }
        remoteTip() == Optional.of(recorded)
    }

    def "appendDecision's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.appendDecision(TASK_ID, new Decision('do it', null, null, Instant.parse('2026-01-01T00:00:00Z')), TaskState.atStageStart('work'))

        then:
        1 * delegate.appendDecision(TASK_ID, _, _) >> {
            recorded = commitOnTaskBranch('resumed')
        }
        remoteTip() == Optional.of(recorded)
    }

    // FR3 of make-checkpoint-gate-durable: the approval is a lifecycle write like the others, so
    //     its commit is replicated before the call returns.
    def "approveCheckpoint's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        def gate = new Position.AwaitingApproval('work')
        String recorded = null

        when:
        repository.approveCheckpoint(TASK_ID, gate, TaskState.atStageStart('next'))

        then:
        1 * delegate.approveCheckpoint(TASK_ID, gate, TaskState.atStageStart('next')) >> {
            recorded = commitOnTaskBranch('approved')
        }
        remoteTip() == Optional.of(recorded)
    }

    // FR7 of make-checkpoint-gate-durable: the resumed write is a lifecycle write like the others,
    //     so its commit is replicated before the call returns.
    def "resumeFrom's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.resumeFrom(TASK_ID, TaskState.atStageStart('work'))

        then:
        1 * delegate.resumeFrom(TASK_ID, TaskState.atStageStart('work')) >> {
            recorded = commitOnTaskBranch('resumed')
        }
        remoteTip() == Optional.of(recorded)
    }

    def "recordOutcome's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('work'), 'work'), TrackerWrite.OWED)

        then:
        1 * delegate.recordOutcome(TASK_ID, _, TrackerWrite.OWED) >> {
            recorded = commitOnTaskBranch('paused')
        }
        remoteTip() == Optional.of(recorded)
    }

    // Scenario: git/git-task-persistence / Park commit is pushed before the tracker signal
    def "the push happens before the lifecycle call returns, so a following tracker write sees it"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.recordOutcome(TASK_ID, new TaskOutcome.Escalated(TaskState.atStageStart('work'),
                new EscalationReport.DecisionNeeded(UntrustedText.agent('continue?'), [
                    UntrustedText.agent('yes'),
                    UntrustedText.agent('no')
                ])), TrackerWrite.OWED)
        def tipAtReturn = remoteTip()

        then:
        1 * delegate.recordOutcome(TASK_ID, _, TrackerWrite.OWED) >> {
            recorded = commitOnTaskBranch('escalated')
        }
        tipAtReturn == Optional.of(recorded)
    }
}
