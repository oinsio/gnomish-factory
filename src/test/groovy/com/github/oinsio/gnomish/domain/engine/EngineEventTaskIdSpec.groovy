package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * EngineEvent cross-cutting supertype behaviour: {@code taskId()} is reachable
 * for every one of the seven sealed variants (key-carrying events delegate to
 * {@code key.taskId()}), letting a consumer filter the whole stream by task
 * (UX2), and an exhaustive switch resolves all seven variants (design D7).
 * Implements FR12 of add-stage-engine.
 */
class EngineEventTaskIdSpec extends Specification {

    private static AttemptKey sampleKey() {
        new AttemptKey('t1', 'build', 0)
    }

    private static CheckRef sampleCheckRef() {
        new CheckRef(0, 'command:./gradlew test')
    }

    private static CheckResult sampleCheckResult() {
        new CheckResult(sampleCheckRef(), new Verdict.Pass(), Duration.ofMillis(5))
    }

    private static TaskState sampleState() {
        TaskState.atStageStart('build')
    }

    private static ToolTrace sampleTrace() {
        new ToolTrace(sampleKey(), [])
    }

    private static TaskOutcome sampleOutcome() {
        new TaskOutcome.Completed(sampleState())
    }

    // FR12: taskId() is reachable via the EngineEvent supertype for all seven variants (UX2)
    def "taskId() is reachable via the EngineEvent supertype for #description"() {
        expect: 'the shared accessor returns the run-correlating task id'
        (event as EngineEvent).taskId() == 't1'

        where:
        description         | event
        'RunStarted'        | new EngineEvent.RunStarted('t1', new Position.AtStage('build'), 0)
        'AttemptStarted'    | new EngineEvent.AttemptStarted(sampleKey())
        'ExecutionFinished' | new EngineEvent.ExecutionFinished(sampleKey(), ExecutorUsage.none())
        'CheckStarted'      | new EngineEvent.CheckStarted(sampleKey(), sampleCheckRef())
        'CheckFinished'     | new EngineEvent.CheckFinished(sampleKey(), sampleCheckResult())
        'AttemptFinished'   | new EngineEvent.AttemptFinished(sampleKey(), sampleState(), sampleTrace())
        'TaskFinished'      | new EngineEvent.TaskFinished('t1', sampleOutcome())
    }

    // FR12: key-carrying events delegate taskId() to key.taskId()
    def "key-carrying events delegate taskId() to key.taskId() for #description"() {
        given: 'a key naming task t1'
        def key = sampleKey()

        expect: 'taskId() equals the key task id'
        (event as EngineEvent).taskId() == key.taskId()

        where:
        description         | event
        'AttemptStarted'    | new EngineEvent.AttemptStarted(new AttemptKey('t1', 'build', 0))
        'ExecutionFinished' | new EngineEvent.ExecutionFinished(new AttemptKey('t1', 'build', 0), ExecutorUsage.none())
        'CheckStarted'      | new EngineEvent.CheckStarted(new AttemptKey('t1', 'build', 0), sampleCheckRef())
        'CheckFinished'     | new EngineEvent.CheckFinished(new AttemptKey('t1', 'build', 0), sampleCheckResult())
        'AttemptFinished'   | new EngineEvent.AttemptFinished(new AttemptKey('t1', 'build', 0), sampleState(), sampleTrace())
    }

    // FR12: an exhaustive switch over EngineEvent handles all seven variants
    def "an exhaustive switch over EngineEvent handles all seven variants for #description"() {
        expect: 'the switch resolves each variant to its label'
        label(event as EngineEvent) == description

        where:
        description         | event
        'RunStarted'        | new EngineEvent.RunStarted('t1', new Position.AtStage('build'), 0)
        'AttemptStarted'    | new EngineEvent.AttemptStarted(sampleKey())
        'ExecutionFinished' | new EngineEvent.ExecutionFinished(sampleKey(), ExecutorUsage.none())
        'CheckStarted'      | new EngineEvent.CheckStarted(sampleKey(), sampleCheckRef())
        'CheckFinished'     | new EngineEvent.CheckFinished(sampleKey(), sampleCheckResult())
        'AttemptFinished'   | new EngineEvent.AttemptFinished(sampleKey(), sampleState(), sampleTrace())
        'TaskFinished'      | new EngineEvent.TaskFinished('t1', sampleOutcome())
    }

    private static String label(EngineEvent event) {
        switch (event) {
            case EngineEvent.RunStarted: return 'RunStarted'
            case EngineEvent.AttemptStarted: return 'AttemptStarted'
            case EngineEvent.ExecutionFinished: return 'ExecutionFinished'
            case EngineEvent.CheckStarted: return 'CheckStarted'
            case EngineEvent.CheckFinished: return 'CheckFinished'
            case EngineEvent.AttemptFinished: return 'AttemptFinished'
            case EngineEvent.TaskFinished: return 'TaskFinished'
            default: throw new AssertionError((Object) 'non-exhaustive switch')
        }
    }
}
