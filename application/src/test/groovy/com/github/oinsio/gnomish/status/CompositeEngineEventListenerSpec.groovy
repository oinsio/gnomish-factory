package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import spock.lang.Specification

/**
 * D10 of add-manual-run: CompositeEngineEventListener fans one EngineEvent out to every
 * listener in a fixed list, in order, so EnginePorts' single listener slot can back several
 * cross-cutting listeners (status snapshot now, MDC/logging in section 8).
 */
class CompositeEngineEventListenerSpec extends Specification {

    private static final EngineEvent EVENT = new EngineEvent.RunStarted('task-1', new Position.AtStage('build'), 0)

    def "onEvent delivers the event to every listener, in list order"() {
        given:
        def first = new RecordingEventListener()
        def second = new RecordingEventListener()
        def composite = new CompositeEngineEventListener([first, second])

        when:
        composite.onEvent(EVENT)

        then:
        first.events == [EVENT]
        second.events == [EVENT]
    }

    def "an empty listener list delivers to nobody without throwing"() {
        given:
        def composite = new CompositeEngineEventListener([])

        when:
        composite.onEvent(EVENT)

        then:
        noExceptionThrown()
    }

    def "the listener list is defensively copied — mutating the original does not affect delivery"() {
        given:
        def first = new RecordingEventListener()
        def mutableList = [first]
        def composite = new CompositeEngineEventListener(mutableList)
        def second = new RecordingEventListener()

        when:
        mutableList << second
        composite.onEvent(EVENT)

        then:
        first.events == [EVENT]
        second.events == []
    }
}
