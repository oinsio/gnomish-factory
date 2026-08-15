package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import org.slf4j.MDC
import spock.lang.Specification

/**
 * MdcEventListener: an EngineEventListener adapter that maintains the stage/attempt MDC keys
 * the logging pattern references (design D9): every AttemptKey-carrying event sets both keys
 * from the key; RunStarted sets stage alone when the position is AtStage; TaskFinished clears
 * both. Implements NFR-O1, D9 of add-manual-run.
 */
class MdcEventListenerSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'

    private static AttemptKey key(int attempt = 2, String stage = 'implement') {
        new AttemptKey(TASK_ID, stage, attempt)
    }

    def cleanup() {
        MDC.clear()
    }

    // NFR-O1, D9: AttemptStarted sets stage/attempt from the key
    def "AttemptStarted sets stage and attempt from the key"() {
        given:
        def listener = new MdcEventListener()

        when:
        listener.onEvent(new EngineEvent.AttemptStarted(key(3, 'review')))

        then:
        MDC.get('stage') == 'review'
        MDC.get('attempt') == '3'
    }

    // NFR-O1, D9: ExecutionFinished sets stage/attempt from the key
    def "ExecutionFinished sets stage and attempt from the key"() {
        given:
        def listener = new MdcEventListener()

        when:
        listener.onEvent(new EngineEvent.ExecutionFinished(key(1, 'implement'), ExecutorUsage.none()))

        then:
        MDC.get('stage') == 'implement'
        MDC.get('attempt') == '1'
    }

    // NFR-O1, D9: CheckStarted sets stage/attempt from the key
    def "CheckStarted sets stage and attempt from the key"() {
        given:
        def listener = new MdcEventListener()

        when:
        listener.onEvent(new EngineEvent.CheckStarted(key(0, 'build'), new CheckRef(0, 'builtin:files_exist')))

        then:
        MDC.get('stage') == 'build'
        MDC.get('attempt') == '0'
    }

    // NFR-O1, D9: CheckFinished sets stage/attempt from the key
    def "CheckFinished sets stage and attempt from the key"() {
        given:
        def listener = new MdcEventListener()
        def result = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))

        when:
        listener.onEvent(new EngineEvent.CheckFinished(key(4, 'build'), result))

        then:
        MDC.get('stage') == 'build'
        MDC.get('attempt') == '4'
    }

    // NFR-O1, D9: AttemptFinished sets stage/attempt from the key
    def "AttemptFinished sets stage and attempt from the key"() {
        given:
        def listener = new MdcEventListener()
        def newState = TaskState.atStageStart('implement')

        when:
        listener.onEvent(new EngineEvent.AttemptFinished(key(2, 'implement'), newState, new ToolTrace(key(2, 'implement'), [])))

        then:
        MDC.get('stage') == 'implement'
        MDC.get('attempt') == '2'
    }

    // NFR-O1, D9: RunStarted at an AtStage position sets stage, leaves attempt unset
    def "RunStarted at an AtStage position sets stage and leaves attempt unset"() {
        given:
        def listener = new MdcEventListener()

        when:
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))

        then:
        MDC.get('stage') == 'implement'
        MDC.get('attempt') == null
    }

    // NFR-O1, D9: RunStarted at PipelineEnd leaves stage and attempt unset
    def "RunStarted at PipelineEnd leaves stage and attempt unset"() {
        given:
        def listener = new MdcEventListener()

        when:
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.PipelineEnd(), 0))

        then:
        MDC.get('stage') == null
        MDC.get('attempt') == null
    }

    // NFR-O1, D9: TaskFinished clears both stage and attempt
    def "TaskFinished clears stage and attempt"() {
        given:
        def listener = new MdcEventListener()
        listener.onEvent(new EngineEvent.AttemptStarted(key(5, 'implement')))
        def outcome = new TaskOutcome.Completed(TaskState.atStageStart('implement'))

        when:
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then:
        MDC.get('stage') == null
        MDC.get('attempt') == null
    }
}
