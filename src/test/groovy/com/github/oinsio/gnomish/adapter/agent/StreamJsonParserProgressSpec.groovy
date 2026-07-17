package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification

/**
 * StreamJsonParser's live progress SPI (design D10): the parse loop emits
 * RoundStarted/ToolStarted/RoundFinished inline, as each recognized line is
 * read, to an injected AgentProgressListener — not by scanning the returned
 * event list afterward. Covers ordering on a plain round, top-level-only
 * filtering on a subagent round, a throwing listener not interrupting parsing,
 * and garbage lines producing no spurious progress. Implements FR7, D9, D10 of
 * add-agent-executor.
 */
class StreamJsonParserProgressSpec extends Specification {

    def clock = new VirtualClock()

    // FR7, D10: a plain round emits RoundStarted, one ToolStarted, then RoundFinished, in order
    def "emits RoundStarted, ToolStarted, and RoundFinished in order for the plain-round fixture"() {
        given: 'a recording listener'
        def recorded = []
        AgentProgressListener listener = { event -> recorded << event }
        def parser = new StreamJsonParser(clock, listener)

        when: 'the plain-round fixture is parsed'
        parser.parse(readerOf('plain-round'))

        then: 'RoundStarted carries the model and session id, one ToolStarted for Write, RoundFinished carries the summary'
        recorded.size() == 3
        recorded[0] == new AgentProgressEvent.RoundStarted('claude-fake-main-1', 'fake-session-plain-1')
        recorded[1] == new AgentProgressEvent.ToolStarted('Write')

        and: 'RoundFinished carries the fixture\'s real subtype, tokensByModel derived from modelUsage, and summary'
        def finished = recorded[2] as AgentProgressEvent.RoundFinished
        finished.subtype() == 'success'
        finished.tokensByModel() == ['claude-fake-main-1': new TokenUsage(120, 45, 10, 5)]
        finished.summary() == 'Stage complete: output.txt written.'
    }

    // FR7, D10: only the top-level Task tool call fires ToolStarted; the nested Grep call does not
    def "emits exactly one ToolStarted for the subagent-round fixture's top-level Task call"() {
        given: 'a recording listener'
        def recorded = []
        AgentProgressListener listener = { event -> recorded << event }
        def parser = new StreamJsonParser(clock, listener)

        when: 'the subagent-round fixture is parsed'
        parser.parse(readerOf('subagent-round'))

        then: 'only Task produces ToolStarted, the nested Grep call does not'
        def toolStartedEvents = recorded.findAll { it instanceof AgentProgressEvent.ToolStarted }
        toolStartedEvents == [
            new AgentProgressEvent.ToolStarted('Task')
        ]

        and: 'RoundStarted and RoundFinished still bracket the round'
        recorded.first() instanceof AgentProgressEvent.RoundStarted
        recorded.last() instanceof AgentProgressEvent.RoundFinished
    }

    // FR7, D10: a throwing listener does not interrupt parsing; parse() still returns every event
    def "does not let a throwing listener interrupt parsing or propagate"() {
        given: 'a listener that always throws'
        AgentProgressListener throwing = { event -> throw new RuntimeException('boom') }
        def parser = new StreamJsonParser(clock, throwing)

        when: 'the plain-round fixture is parsed'
        def events = parser.parse(readerOf('plain-round'))

        then: 'no exception propagates and the full event list is still returned'
        noExceptionThrown()
        events.size() == 5
        events.last().event() instanceof AgentEvent.ResultEvent
    }

    // FR7, D10: garbage/unknown lines interleaved with valid ones produce no spurious progress
    def "produces no spurious progress from the garbage-output fixture's malformed lines"() {
        given: 'a recording listener'
        def recorded = []
        AgentProgressListener listener = { event -> recorded << event }
        def parser = new StreamJsonParser(clock, listener)

        when: 'the garbage-output fixture is parsed'
        parser.parse(readerOf('garbage-output'))

        then: 'only progress for the recognized lines is emitted, nothing for the malformed ones'
        recorded.size() == 1
        recorded[0] instanceof AgentProgressEvent.RoundStarted
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = StreamJsonParserProgressSpec.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
