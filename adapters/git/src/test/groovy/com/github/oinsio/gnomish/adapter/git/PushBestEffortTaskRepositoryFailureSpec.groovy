package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
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
            repository.appendDecision(TASK_ID, new Decision('go', null, null, Instant.EPOCH))
        }

        then:
        1 * delegate.appendDecision(TASK_ID, _) >> {
            commitOnTaskBranch('resumed')
        }
        noExceptionThrown()

        and:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.startsWith('lifecycle push failed:')
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
        def events = capture { repository.recordOutcome(TASK_ID, outcome) }

        then:
        1 * delegate.recordOutcome(TASK_ID, _) >> {
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
                new AttemptKey('T-1', 'work', 0), 'violation')
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
            repository.createTask(new TaskContext(TASK_ID, 'title', 'body', []), 'HEAD')
        }

        then:
        1 * delegate.createTask(_, _)
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
                new AttemptKey(TASK_ID, 'work', 0), 'violation'))

        then:
        1 * delegate.recordOutcome(TASK_ID, _) >> {
            throw new IllegalStateException('boom')
        }
        thrown(IllegalStateException)
        remoteTip() == Optional.empty()
    }
}
