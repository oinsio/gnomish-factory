package com.github.oinsio.gnomish.app.lease

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
import spock.lang.Specification

/**
 * HeartbeatProgress: the EngineEventListener that keeps the latest (stage, attempt) per task
 * for the beat payload (design D1). Each key-carrying event records the key; RunStarted
 * records its resolved position; TaskFinished is a no-op; an unseen task reads PENDING.
 *
 * FR1 of add-claim-heartbeat.
 */
class HeartbeatProgressSpec extends Specification {

    private static final String TASK = 'github:o/r#1'

    private final HeartbeatProgress progress = new HeartbeatProgress()

    private static AttemptKey key(String stage, int attempt) {
        new AttemptKey(TASK, stage, attempt)
    }

    // FR1: an unseen task reads the PENDING placeholder, not null.
    def "an unseen task reads the pending placeholder"() {
        expect:
        progress.progressFor(TASK) == HeartbeatProgress.PENDING
        HeartbeatProgress.PENDING == new HeartbeatProgress.Progress('(pending)', 0)
    }

    // FR1: RunStarted at a stage records the stage name and the attempts already burned,
    //     so a beat before the first attempt event still names where the work is.
    def "RunStarted at a stage records the stage and burned attempts"() {
        when:
        progress.onEvent(new EngineEvent.RunStarted(TASK, new Position.AtStage('plan'), 2))

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress('plan', 2)
    }

    // FR1: RunStarted at PipelineEnd records the explicit end marker as the stage.
    def "RunStarted at PipelineEnd records the end marker"() {
        when:
        progress.onEvent(new EngineEvent.RunStarted(TASK, new Position.PipelineEnd(), 0))

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress('(end)', 0)
    }

    // FR1: every key-carrying event records its key's stage and attempt verbatim.
    def "#event records the key's stage and attempt"() {
        when:
        progress.onEvent(event)

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress(stage, attempt)

        where:
        stage | attempt | event
        'implement' | 3 | new EngineEvent.AttemptStarted(key('implement', 3))
        'implement' | 1 | new EngineEvent.ExecutionFinished(key('implement', 1), ExecutorUsage.none())
        'build' | 0 | new EngineEvent.CheckStarted(key('build', 0), new CheckRef(0, 'builtin:files_exist'))
        'build' | 4 | new EngineEvent.CheckFinished(key('build', 4), passResult())
        'review' | 2 | new EngineEvent.AttemptFinished(key('review', 2), TaskState.atStageStart('review'), trace('review', 2))
    }

    // FR1: the LATEST event wins — a later stage/attempt overwrites an earlier snapshot.
    def "the latest event overwrites the earlier snapshot"() {
        given:
        progress.onEvent(new EngineEvent.AttemptStarted(key('plan', 0)))

        when:
        progress.onEvent(new EngineEvent.AttemptStarted(key('review', 2)))

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress('review', 2)
    }

    // FR1: TaskFinished carries no (stage, attempt) — the last snapshot stands unchanged.
    def "TaskFinished leaves the last snapshot unchanged"() {
        given:
        progress.onEvent(new EngineEvent.AttemptStarted(key('implement', 5)))

        when:
        progress.onEvent(new EngineEvent.TaskFinished(TASK, new TaskOutcome.Completed(TaskState.atStageStart('implement'))))

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress('implement', 5)
    }

    // FR1: progress is kept per task — one task's events never leak into another's snapshot.
    def "progress is tracked independently per task"() {
        given:
        def other = 'github:o/r#2'
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(TASK, 'plan', 0)))

        when:
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(other, 'review', 1)))

        then:
        progress.progressFor(TASK) == new HeartbeatProgress.Progress('plan', 0)
        progress.progressFor(other) == new HeartbeatProgress.Progress('review', 1)
    }

    private static CheckResult passResult() {
        new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
    }

    private static ToolTrace trace(String stage, int attempt) {
        new ToolTrace(new AttemptKey(TASK, stage, attempt), [])
    }
}
