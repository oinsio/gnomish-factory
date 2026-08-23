package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, NFR-O1 of fix-lifecycle-push (design D1's port-shape note): the wider host-side port
 * gets the same push after each of the three base lifecycle writes, plus one after the
 * tracker-write-confirmed commit that only this port records. Shares {@link LifecyclePushFixture}
 * with the {@code TaskRepository} decorator's own specs.
 */
class PushBestEffortTaskLifecycleStoreSpec extends Specification implements LifecyclePushFixture {

    @TempDir
    Path tempDir

    def setup() {
        initLifecyclePushFixture()
    }

    private TaskLifecycleStore decorated(TaskLifecycleStore delegate) {
        new PushBestEffortTaskLifecycleStore(delegate, git, cloneDir)
    }

    def "the confirm-terminal-write commit reaches origin"() {
        given:
        def delegate = Mock(TaskLifecycleStore)
        def store = decorated(delegate)
        String recorded = null

        when:
        store.confirmTerminalWrite(TASK_ID)

        then:
        1 * delegate.confirmTerminalWrite(TASK_ID) >> {
            recorded = commitOnTaskBranch('confirmed')
        }
        remoteTip() == Optional.of(recorded)
    }

    def "a failed confirm push names the confirm event in its single WARN"() {
        given:
        gitOutput(cloneDir, 'remote', 'set-url', 'origin', tempDir.resolve('nowhere.git').toString())
        def delegate = Mock(TaskLifecycleStore)
        def store = decorated(delegate)

        when:
        def events = capture { store.confirmTerminalWrite(TASK_ID) }

        then:
        1 * delegate.confirmTerminalWrite(TASK_ID) >> {
            commitOnTaskBranch('confirmed')
        }
        noExceptionThrown()

        and:
        events.size() == 1
        events[0].level == Level.WARN
        events[0].formattedMessage.contains("taskId=${TASK_ID}")
        events[0].formattedMessage.contains("branch=${BRANCH}")
        events[0].formattedMessage.contains('event=TRACKER_WRITE_CONFIRMED')
    }

    def "the three base lifecycle writes push exactly as the narrower port's decorator does"() {
        given:
        def delegate = Mock(TaskLifecycleStore)
        def store = decorated(delegate)
        String recorded = null

        when:
        write.call(store)

        then: 'the write reached THAT delegate method — named per row, not one of three'
        1 * delegate."$delegated"(_, _) >> {
            recorded = commitOnTaskBranch(label)
        }
        remoteTip() == Optional.of(recorded)

        where:
        label | delegated | write
        'started' | 'createTask' | { TaskLifecycleStore s ->
            s.createTask(new TaskContext(TASK_ID, 't', 'b', []), 'HEAD')
        }
        'resumed' | 'appendDecision' | { TaskLifecycleStore s ->
            s.appendDecision(TASK_ID, new Decision('go', null, null, Instant.EPOCH))
        }
        'paused' | 'recordOutcome' | { TaskLifecycleStore s ->
            s.recordOutcome(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('work'), 'work'))
        }
    }
}
