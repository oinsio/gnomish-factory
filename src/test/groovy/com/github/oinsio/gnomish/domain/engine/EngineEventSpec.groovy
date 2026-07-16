package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * EngineEvent: the seven sealed events the engine emits per run, each exposing
 * its components as constructed and validating its bookend fields (run-level
 * bookends carry {@code taskId}; per-attempt events carry the full
 * {@code AttemptKey}, design D7). Cross-cutting {@code taskId()} reachability
 * lives in {@code EngineEventTaskIdSpec}. Implements FR12 of add-stage-engine.
 */
class EngineEventSpec extends Specification {

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

    // FR12: RunStarted exposes position + attemptsUsed as constructed
    def "RunStarted exposes its components as constructed"() {
        given: 'a position'
        def position = new Position.AtStage('build')

        when: 'a RunStarted event is created'
        def event = new EngineEvent.RunStarted('t1', position, 2)

        then: 'the components are exposed exactly as constructed'
        event.taskId() == 't1'
        event.position() == position
        event.attemptsUsed() == 2
    }

    // FR12: RunStarted's validated attemptsUsed round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "RunStarted attemptsUsed round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero burn count it was built with'
        new EngineEvent.RunStarted('t', new Position.AtStage('s'), 3).attemptsUsed() == 3
    }

    // FR12: AttemptStarted carries only the key
    def "AttemptStarted exposes its key as constructed"() {
        given: 'a key'
        def key = sampleKey()

        when: 'an AttemptStarted event is created'
        def event = new EngineEvent.AttemptStarted(key)

        then: 'the key is exposed exactly as constructed'
        event.key() == key
    }

    // FR12: ExecutionFinished carries the key + usage
    def "ExecutionFinished exposes its components as constructed"() {
        given: 'a key and usage'
        def key = sampleKey()
        def usage = ExecutorUsage.none()

        when: 'an ExecutionFinished event is created'
        def event = new EngineEvent.ExecutionFinished(key, usage)

        then: 'the components are exposed exactly as constructed'
        event.key() == key
        event.usage() == usage
    }

    // FR12: CheckStarted carries the key + checkRef
    def "CheckStarted exposes its components as constructed"() {
        given: 'a key and a check reference'
        def key = sampleKey()
        def check = sampleCheckRef()

        when: 'a CheckStarted event is created'
        def event = new EngineEvent.CheckStarted(key, check)

        then: 'the components are exposed exactly as constructed'
        event.key() == key
        event.check() == check
    }

    // FR12: CheckFinished carries the key + a CheckResult (checkRef + verdict + duration)
    def "CheckFinished exposes its components as constructed"() {
        given: 'a key and a check result'
        def key = sampleKey()
        def result = sampleCheckResult()

        when: 'a CheckFinished event is created'
        def event = new EngineEvent.CheckFinished(key, result)

        then: 'the components are exposed exactly as constructed'
        event.key() == key
        event.result() == result
    }

    // FR12: AttemptFinished carries the key + new state + trace; round result is the last attempts entry
    def "AttemptFinished exposes its components as constructed"() {
        given: 'a key, a new state and a trace'
        def key = sampleKey()
        def state = sampleState()
        def trace = sampleTrace()

        when: 'an AttemptFinished event is created'
        def event = new EngineEvent.AttemptFinished(key, state, trace)

        then: 'the components are exposed exactly as constructed'
        event.key() == key
        event.newState() == state
        event.trace() == trace
    }

    // FR12: TaskFinished exposes taskId + the terminal outcome as constructed
    def "TaskFinished exposes its components as constructed"() {
        given: 'an outcome'
        def outcome = sampleOutcome()

        when: 'a TaskFinished event is created'
        def event = new EngineEvent.TaskFinished('t1', outcome)

        then: 'the components are exposed exactly as constructed'
        event.taskId() == 't1'
        event.outcome() == outcome
    }

    // FR12: RunStarted rejects a blank taskId and TaskFinished rejects a blank taskId (D7 bookend integrity)
    def "run-level bookend rejects a blank taskId for #description"() {
        when: 'the bookend is created with a blank taskId'
        create.call()

        then: 'the failure names the component'
        def e = thrown(IllegalArgumentException)
        e.message.contains('taskId')

        where:
        description   | create
        'RunStarted'  | { new EngineEvent.RunStarted('  ', new Position.AtStage('build'), 0) }
        'TaskFinished' | { new EngineEvent.TaskFinished('', sampleOutcome()) }
    }

    // FR12: RunStarted rejects a negative attemptsUsed
    def "RunStarted rejects a negative attemptsUsed"() {
        when: 'a RunStarted is created with a negative attemptsUsed'
        new EngineEvent.RunStarted('t1', new Position.AtStage('build'), -1)

        then: 'the failure names the component'
        def e = thrown(IllegalArgumentException)
        e.message.contains('attemptsUsed')
    }

    // FR12/D7: RunStarted accepts a PipelineEnd bookend position (immediate completion)
    def "RunStarted accepts both an AtStage and a PipelineEnd position"() {
        expect: 'the pipeline-end bookend case is accepted'
        new EngineEvent.RunStarted('t1', position, 0).position() == position

        where:
        position << [
            new Position.AtStage('build'),
            new Position.PipelineEnd()
        ]
    }

    // FR12: value equality holds for representative variants
    def "RunStarted has value equality"() {
        given: 'two RunStarted events with equal components'
        def a = new EngineEvent.RunStarted('t1', new Position.AtStage('build'), 2)
        def b = new EngineEvent.RunStarted('t1', new Position.AtStage('build'), 2)
        expect: 'they are equal and share a hash code'
        a == b
        a.hashCode() == b.hashCode()
    }

    def "CheckFinished has value equality"() {
        given: 'two CheckFinished events with equal components'
        def a = new EngineEvent.CheckFinished(sampleKey(), sampleCheckResult())
        def b = new EngineEvent.CheckFinished(sampleKey(), sampleCheckResult())

        expect: 'they are equal and share a hash code'
        a == b
        a.hashCode() == b.hashCode()
    }
}
