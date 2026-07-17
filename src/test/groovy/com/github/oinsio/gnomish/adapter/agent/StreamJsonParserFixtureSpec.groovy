package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification

/**
 * StreamJsonParser against the committed fake-agent fixtures (real-shaped
 * stream-json, `src/test/resources/fake-agent/scenarios/`): an end-to-end proof
 * that the tolerant parse loop copes with the real wire shapes the fake agent
 * (and, per design D11, the real CLI) emits — plain round, subagent nesting,
 * and garbage/unknown lines mixed into an otherwise valid stream.
 *
 * <p>Implements FR4, D3 of add-agent-executor.
 */
class StreamJsonParserFixtureSpec extends Specification {

    def parser = new StreamJsonParser(new VirtualClock())

    // FR4, D3: a clean round fixture parses into the full init/assistant/user/result sequence
    def "parses the plain-round fixture into the full event sequence"() {
        when: 'the plain-round fixture is parsed'
        def events = parser.parse(readerOf('plain-round')).collect { it.event() }

        then: 'init, assistant (tool_use), user (tool_result), assistant (final text), result — in order'
        events.size() == 5
        events[0] instanceof AgentEvent.InitEvent
        events[1] instanceof AgentEvent.AssistantEvent
        (events[1] as AgentEvent.AssistantEvent).content().any { it instanceof ContentBlock.ToolUse }
        events[2] instanceof AgentEvent.UserEvent
        (events[2] as AgentEvent.UserEvent).content().any { it instanceof ContentBlock.ToolResult }
        events[3] instanceof AgentEvent.AssistantEvent
        events[4] instanceof AgentEvent.ResultEvent

        and: 'the result event carries usage and a non-null modelUsage'
        def result = events[4] as AgentEvent.ResultEvent
        result.result() == 'Stage complete: output.txt written.'
        result.usage() != null
        result.modelUsage() != null
        result.modelUsage()['claude-fake-main-1'] != null
    }

    // FR4, D3: subagent nesting is preserved via parentToolUseId, top-level events have none
    def "parses the subagent-round fixture, preserving parentToolUseId on nested events only"() {
        when: 'the subagent-round fixture is parsed'
        def events = parser.parse(readerOf('subagent-round')).collect { it.event() }

        then: 'top-level events carry a null parentToolUseId, nested ones carry the top-level tool_use id'
        def topLevelInit = events[0] as AgentEvent.InitEvent
        topLevelInit.sessionId() == 'fake-session-subagent-1'

        def topLevelAssistant = events[1] as AgentEvent.AssistantEvent
        topLevelAssistant.parentToolUseId() == null

        def nestedAssistant = events[2] as AgentEvent.AssistantEvent
        nestedAssistant.parentToolUseId() == 'toolu_top_1'

        def nestedUser = events[3] as AgentEvent.UserEvent
        nestedUser.parentToolUseId() == 'toolu_top_1'

        and: 'the result event carries per-model modelUsage for both models'
        def result = events.last() as AgentEvent.ResultEvent
        result.modelUsage().keySet() == [
            'claude-fake-main-1',
            'claude-fake-sub-1'
        ] as Set
    }

    // FR4, D3: garbage/unknown lines mixed with valid ones are tolerated end to end
    def "parses the garbage-output fixture, keeping only the recognized lines"() {
        when: 'the garbage-output fixture is parsed'
        def events = parser.parse(readerOf('garbage-output')).collect { it.event() }

        then: 'the non-JSON, unknown-type, and unterminated lines are dropped without throwing'
        events.size() == 2
        events[0] instanceof AgentEvent.InitEvent
        events[1] instanceof AgentEvent.AssistantEvent
    }

    // FR4, D3: a stream ending without a result event yields no ResultEvent, no exception
    def "parses the missing-result-event fixture without producing a ResultEvent"() {
        when: 'the missing-result-event fixture is parsed'
        def events = parser.parse(readerOf('missing-result-event')).collect { it.event() }

        then: 'both prior events parse, no ResultEvent is present'
        events.size() == 2
        events.every { !(it instanceof AgentEvent.ResultEvent) }
    }

    // FR6, NFR-O3, D3: read-time instants are non-decreasing across the fixture's lines
    def "stamps the plain-round fixture's events with non-decreasing read-time instants"() {
        when: 'the plain-round fixture is parsed'
        def timestamped = parser.parse(readerOf('plain-round'))

        then: 'every event carries a readAt instant, in non-decreasing wire order'
        timestamped.size() == 5
        timestamped.every { it.readAt() != null }
        (1..<timestamped.size()).every { i -> !timestamped[i].readAt().isBefore(timestamped[i - 1].readAt()) }
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = StreamJsonParserFixtureSpec.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
