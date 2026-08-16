package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Sanity spec for the engine support fakes: the recording event listener,
 * in-memory attempt persistence and virtual-time (clock + sleeper) fakes.
 * Exercises their recording, throw-mode and time-advance behavior so the
 * fakes are proven to compile and work for the section 4–7 orchestration
 * specs. These are test fakes for the add-stage-engine ports, not production
 * code.
 */
class FakesSupportSanitySpec extends Specification {

    private static ToolTrace trace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    }

    def "RecordingEventListener records every event"() {
        given: 'a plain recording listener'
        def listener = new RecordingEventListener()

        when: 'two events are delivered'
        listener.onEvent(new EngineEvent.TaskFinished('TASK-1', null))
        listener.onEvent(new EngineEvent.RunStarted('TASK-1', new Position.AtStage('build'), 0))

        then: 'both are recorded in order'
        listener.events.size() == 2
    }

    def "RecordingEventListener with the throw flag throws after recording"() {
        given: 'a listener set to throw on every event'
        def listener = new RecordingEventListener()
        listener.throwOnEvent = true

        when: 'an event is delivered'
        listener.onEvent(new EngineEvent.TaskFinished('TASK-1', null))

        then: 'it throws yet still recorded the event first'
        thrown(RuntimeException)
        listener.events.size() == 1
    }

    def "InMemoryAttemptPersistence records each persist call in order"() {
        given: 'a persistence fake'
        def persistence = new InMemoryAttemptPersistence()
        def state = TaskState.atStageStart('build')

        when: 'two rounds are persisted'
        persistence.persist('TASK-1', state, trace())
        persistence.persist('TASK-2', state, trace())

        then: 'both entries are recorded in order'
        persistence.entries*.taskId == ['TASK-1', 'TASK-2']
    }

    def "InMemoryAttemptPersistence throws on the configured Nth persist"() {
        given: 'a persistence fake set to fail on the second call'
        def persistence = new InMemoryAttemptPersistence()
        persistence.failOnCall = 2
        def state = TaskState.atStageStart('build')

        when: 'the first persist succeeds'
        persistence.persist('TASK-1', state, trace())

        then: 'no failure yet'
        noExceptionThrown()

        when: 'the second persist runs'
        persistence.persist('TASK-1', state, trace())

        then: 'it throws and the failing call was still recorded'
        thrown(RuntimeException)
        persistence.entries.size() == 2
    }

    def "VirtualSleeper advances the VirtualClock and records slept durations"() {
        given: 'a virtual clock and a sleeper sharing it'
        def clock = new VirtualClock()
        def sleeper = new VirtualSleeper(clock)

        expect: 'the clock starts at the epoch'
        clock.now() == Instant.EPOCH

        when: 'the sleeper sleeps twice'
        sleeper.sleep(Duration.ofSeconds(5))
        sleeper.sleep(Duration.ofSeconds(3))

        then: 'virtual time advanced by the total and each duration was recorded'
        clock.now() == Instant.EPOCH.plusSeconds(8)
        sleeper.slept == [
            Duration.ofSeconds(5),
            Duration.ofSeconds(3)
        ]
    }

    def "VirtualClock advance moves time forward independently"() {
        given: 'a virtual clock'
        def clock = new VirtualClock()

        when: 'time is advanced'
        clock.advance(Duration.ofMinutes(2))

        then: 'now reflects the advance'
        clock.now() == Instant.EPOCH.plusSeconds(120)
    }
}
