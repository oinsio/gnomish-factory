package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * FR7, D10 of add-agent-executor: {@link CompositeAgentProgressListener} fans one
 * {@link AgentProgressEvent} out to every wrapped listener, in order, and — unlike
 * {@link com.github.oinsio.gnomish.status.CompositeEngineEventListener}, which relies
 * on {@code Events.emit}'s single outer try/catch — must swallow each child's own
 * exception itself: {@link StreamJsonParser#deliver} wraps only the one call it makes
 * to whichever listener {@code EnginePorts}/the app assembly hands it, so if that one
 * listener is this composite, a child throwing must never stop delivery to the
 * children after it in the same fan-out.
 */
class CompositeAgentProgressListenerSpec extends Specification {

    private static final AgentProgressEvent EVENT = new AgentProgressEvent.ToolStarted('Read')

    def "onProgress delivers the event to every listener, in list order"() {
        given:
        def first = new RecordingAgentProgressListener()
        def second = new RecordingAgentProgressListener()
        def composite = new CompositeAgentProgressListener([first, second])

        when:
        composite.onProgress(EVENT)

        then:
        first.events == [EVENT]
        second.events == [EVENT]
    }

    def "an empty listener list delivers to nobody without throwing"() {
        given:
        def composite = new CompositeAgentProgressListener([])

        when:
        composite.onProgress(EVENT)

        then:
        noExceptionThrown()
    }

    def "a listener that throws does not block delivery to the listeners after it"() {
        given:
        def before = new RecordingAgentProgressListener()
        def throwing = { AgentProgressEvent event -> throw new RuntimeException('boom') } as AgentProgressListener
        def after = new RecordingAgentProgressListener()
        def composite = new CompositeAgentProgressListener([before, throwing, after])

        when:
        composite.onProgress(EVENT)

        then:
        noExceptionThrown()
        before.events == [EVENT]
        after.events == [EVENT]
    }

    def "the listener list is defensively copied — mutating the original does not affect delivery"() {
        given:
        def first = new RecordingAgentProgressListener()
        def mutableList = [first]
        def composite = new CompositeAgentProgressListener(mutableList)
        def second = new RecordingAgentProgressListener()

        when:
        mutableList << second
        composite.onProgress(EVENT)

        then:
        first.events == [EVENT]
        second.events == []
    }

    private static final class RecordingAgentProgressListener implements AgentProgressListener {
        final List<AgentProgressEvent> events = []

        @Override
        void onProgress(AgentProgressEvent event) {
            events << event
        }
    }
}
