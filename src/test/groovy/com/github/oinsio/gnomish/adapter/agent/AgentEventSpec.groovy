package com.github.oinsio.gnomish.adapter.agent

import spock.lang.Specification

/**
 * AgentEvent: the sealed stream-json event variants (design D3) — construction,
 * defensive copy of content/usage/modelUsage, and the null-vs-present
 * distinctions {@code parentToolUseId}/{@code modelUsage} preserve for later
 * tasks. Implements FR4, D3 of add-agent-executor.
 */
class AgentEventSpec extends Specification {

    // FR4, D3: InitEvent exposes sessionId and model as constructed
    def "InitEvent exposes sessionId and model as constructed"() {
        expect:
        new AgentEvent.InitEvent('sess-1', 'claude-x').sessionId() == 'sess-1'
        new AgentEvent.InitEvent('sess-1', 'claude-x').model() == 'claude-x'
    }

    // FR4: InitEvent rejects a blank sessionId or model
    def "InitEvent rejects a blank sessionId or model"() {
        when:
        new AgentEvent.InitEvent(sessionId, model)

        then:
        thrown(IllegalArgumentException)

        where:
        sessionId | model
        ''        | 'claude-x'
        'sess-1'  | ''
    }

    // FR4, D3: AssistantEvent accepts a null parentToolUseId for a top-level event
    def "AssistantEvent accepts a null parentToolUseId for a top-level event"() {
        when:
        def event = new AgentEvent.AssistantEvent('sess-1', null, 'claude-x', [])

        then:
        event.parentToolUseId() == null
    }

    // FR4, D3: AssistantEvent preserves a present parentToolUseId for a nested event
    def "AssistantEvent preserves a present parentToolUseId for a nested event"() {
        when:
        def event = new AgentEvent.AssistantEvent('sess-1', 'toolu_top_1', 'claude-sub', [])

        then:
        event.parentToolUseId() == 'toolu_top_1'
    }

    // FR4: AssistantEvent content is defensively copied — later source mutation cannot leak in
    def "AssistantEvent defensively copies content from the source list"() {
        given:
        def source = [new ContentBlock.Text('hi')] as List<ContentBlock>

        when:
        def event = new AgentEvent.AssistantEvent('sess-1', null, 'claude-x', source)
        source.add(new ContentBlock.Text('added later'))

        then:
        event.content().size() == 1
    }

    // FR4, D3: UserEvent preserves a present or absent parentToolUseId distinctly
    def "UserEvent preserves parentToolUseId presence distinctly from absence"() {
        expect:
        new AgentEvent.UserEvent('sess-1', null, []).parentToolUseId() == null
        new AgentEvent.UserEvent('sess-1', 'toolu_top_1', []).parentToolUseId() == 'toolu_top_1'
    }

    // FR4, D3: ResultEvent exposes result, usage and modelUsage as constructed
    def "ResultEvent exposes result, usage and modelUsage as constructed"() {
        given:
        def usage = [input_tokens: 10]
        def modelUsage = ['claude-x': [inputTokens: 10]]

        when:
        def event = new AgentEvent.ResultEvent('sess-1', 'success', 'done', usage, modelUsage)

        then:
        event.result() == 'done'
        event.usage() == usage
        event.modelUsage() == modelUsage
    }

    // FR5, D4: ResultEvent preserves a null modelUsage distinctly from an empty map (fallback signal for task 3.3)
    def "ResultEvent preserves a null modelUsage distinctly from an empty map"() {
        expect:
        new AgentEvent.ResultEvent('sess-1', 'success', 'done', [:], null).modelUsage() == null
        new AgentEvent.ResultEvent('sess-1', 'success', 'done', [:], [:]).modelUsage() == [:]
    }

    // FR4: ResultEvent rejects a blank sessionId
    def "ResultEvent rejects a blank sessionId"() {
        when:
        new AgentEvent.ResultEvent('', 'success', 'done', null, null)

        then:
        thrown(IllegalArgumentException)
    }
}
