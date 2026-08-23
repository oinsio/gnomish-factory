package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
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
        new PushBestEffortTaskRepository(delegate, git, cloneDir)
    }

    def "createTask's commit reaches origin"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.createTask(new TaskContext(TASK_ID, 'title', 'body', []), 'HEAD')

        then:
        1 * delegate.createTask(_, 'HEAD') >> {
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
        repository.appendDecision(TASK_ID, new Decision('do it', null, null, Instant.parse('2026-01-01T00:00:00Z')))

        then:
        1 * delegate.appendDecision(TASK_ID, _) >> {
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
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('work'), 'work'))

        then:
        1 * delegate.recordOutcome(TASK_ID, _) >> {
            recorded = commitOnTaskBranch('paused')
        }
        remoteTip() == Optional.of(recorded)
    }

    def "a Completed outcome and the cleanup commit behind it share one push of the resulting tip"() {
        given: 'a delegate that records the outcome and its cleanup commit, as the git repositories do'
        def delegate = Mock(TaskRepository)
        def log = tempDir.resolve('argv.log')
        def repository = new PushBestEffortTaskRepository(delegate,
                new GitProcessRunner(recordingGit(log).toString()), cloneDir)
        String cleanupTip = null

        when:
        repository.recordOutcome(TASK_ID, new TaskOutcome.Completed(TaskState.atStageStart('work')))

        then:
        1 * delegate.recordOutcome(TASK_ID, _) >> {
            commitOnTaskBranch('completed')
            cleanupTip = commitOnTaskBranch('cleanup')
        }

        and: 'ONE push ran for the pair (FR1), not one per commit — counted over the argv itself'
        // The rev-parse between them is the runner's own clone-key read for the mutation lock a
        // push takes (design D8 of add-factory-serve), not a second remote round-trip.
        recordedSubcommands(log) == ['remote', 'rev-parse', 'push']

        and: 'and it delivered the cleanup commit at the tip'
        remoteTip() == Optional.of(cleanupTip)
    }

    def "the push happens before the lifecycle call returns, so a following tracker write sees it"() {
        given:
        def delegate = Mock(TaskRepository)
        def repository = decorated(delegate)
        String recorded = null

        when:
        repository.recordOutcome(TASK_ID, new TaskOutcome.Escalated(TaskState.atStageStart('work'),
                new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])))
        def tipAtReturn = remoteTip()

        then:
        1 * delegate.recordOutcome(TASK_ID, _) >> {
            recorded = commitOnTaskBranch('escalated')
        }
        tipAtReturn == Optional.of(recorded)
    }
}
