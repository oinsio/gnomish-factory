package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification

/**
 * TokenUsageMapper: FR5/D4's {@code modelUsage} → {@code tokensByModel}
 * derivation, with the {@code usage}-keyed-by-init-model fallback and the
 * silent-degrade-to-empty-map path when neither wire shape is interpretable
 * (NFR-R2 — telemetry trouble never throws).
 */
class TokenUsageMapperSpec extends Specification {

    def mapper = new TokenUsageMapper()
    def parser = new StreamJsonParser(new VirtualClock())

    // FR5, D4: modelUsage present, single model — preferred path, matches modelUsage not flat usage
    def "maps the plain-round fixture's modelUsage to a single-entry tokensByModel"() {
        given: 'the plain-round fixture parsed into events'
        def events = parser.parse(readerOf('plain-round'))
        def resultEvent = events.collect { it.event() }.find { it instanceof AgentEvent.ResultEvent } as AgentEvent.ResultEvent
        def initEvent = events.collect { it.event() }.find { it instanceof AgentEvent.InitEvent } as AgentEvent.InitEvent

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'modelUsage is authoritative, keyed by the resolved model id'
        tokensByModel == ['claude-fake-main-1': new TokenUsage(120, 45, 10, 5)]
    }

    // FR5, D4: modelUsage present with TWO model keys — proves modelUsage (not the flat usage sum) is authoritative
    def "maps the subagent-round fixture's modelUsage to a two-entry tokensByModel, not the flat usage sum"() {
        given: 'the subagent-round fixture parsed into events'
        def events = parser.parse(readerOf('subagent-round'))
        def resultEvent = events.collect { it.event() }.find { it instanceof AgentEvent.ResultEvent } as AgentEvent.ResultEvent
        def initEvent = events.collect { it.event() }.find { it instanceof AgentEvent.InitEvent } as AgentEvent.InitEvent

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'every modelUsage key becomes an entry with its own values, not the summed flat usage'
        tokensByModel == [
            'claude-fake-main-1': new TokenUsage(200, 60, 15, 10),
            'claude-fake-sub-1' : new TokenUsage(100, 30, 5, 5),
        ]
    }

    // FR5, D4: another single-model modelUsage fixture
    def "maps the judge-verdict-pass fixture's modelUsage to a single-entry tokensByModel"() {
        given: 'the judge-verdict-pass fixture parsed into events'
        def events = parser.parse(readerOf('judge-verdict-pass'))
        def resultEvent = events.collect { it.event() }.find { it instanceof AgentEvent.ResultEvent } as AgentEvent.ResultEvent
        def initEvent = events.collect { it.event() }.find { it instanceof AgentEvent.InitEvent } as AgentEvent.InitEvent

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the single modelUsage entry is mapped'
        tokensByModel == ['claude-fake-judge-1': new TokenUsage(150, 25, 0, 0)]
    }

    // FR5, D4: modelUsage entirely absent (older CLI) — fall back to flat usage keyed by the init event's model
    def "falls back to the flat usage object keyed by the init event's model when modelUsage is absent"() {
        given: 'a synthetic result event with usage present but modelUsage null (key omitted from the wire)'
        def resultEvent = new AgentEvent.ResultEvent(
                'fake-session-fallback-1', 'done',
                [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5],
                null)
        def initEvent = new AgentEvent.InitEvent('fake-session-fallback-1', 'claude-fake-legacy-1')

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the flat usage is keyed by the init event\'s main model'
        tokensByModel == ['claude-fake-legacy-1': new TokenUsage(120, 45, 10, 5)]
    }

    // NFR-R2, FR4: neither modelUsage nor usage present/interpretable — degrade to empty map, never throw
    def "degrades to an empty tokensByModel when neither modelUsage nor usage is present"() {
        given: 'a synthetic result event with neither usage nor modelUsage'
        def resultEvent = new AgentEvent.ResultEvent('fake-session-empty-1', 'done', null, null)
        def initEvent = new AgentEvent.InitEvent('fake-session-empty-1', 'claude-fake-main-1')

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the map degrades to empty, no exception'
        tokensByModel == [:]
    }

    // NFR-R2, FR4: modelUsage absent, usage present, but no init event available to key the fallback
    def "degrades to an empty tokensByModel when the fallback usage cannot be keyed (no init event)"() {
        given: 'a synthetic result event with a usable flat usage but no init event supplied'
        def resultEvent = new AgentEvent.ResultEvent(
                'fake-session-no-init-1', 'done',
                [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5],
                null)

        when: 'tokens are mapped with a null init event'
        def tokensByModel = mapper.toTokensByModel(resultEvent, null)

        then: 'the map degrades to empty rather than guessing a key, no exception'
        tokensByModel == [:]
    }

    // NFR-R2, FR4: a malformed modelUsage entry (missing a field) is skipped per-entry, siblings still map
    def "skips a malformed modelUsage entry but keeps mapping the other well-formed entries"() {
        given: 'a synthetic result event where one model entry is missing a required field'
        def resultEvent = new AgentEvent.ResultEvent(
                'fake-session-partial-1', 'done',
                null,
                [
                    'claude-fake-good-1': [inputTokens: 100, outputTokens: 20, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
                    'claude-fake-bad-1' : [inputTokens: 100, outputTokens: 'not-a-number', cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
                ])
        def initEvent = new AgentEvent.InitEvent('fake-session-partial-1', 'claude-fake-main-1')

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the well-formed entry maps, the malformed one is skipped, no exception'
        tokensByModel == ['claude-fake-good-1': new TokenUsage(100, 20, 0, 0)]
    }

    // NFR-R2, FR4: a modelUsage entry whose value is not even a map degrades that entry only
    def "skips a modelUsage entry that is not a map at all"() {
        given: 'a synthetic result event with one entry a plain string instead of a map'
        def resultEvent = new AgentEvent.ResultEvent(
                'fake-session-notmap-1', 'done',
                null,
                [
                    'claude-fake-good-1': [inputTokens: 5, outputTokens: 5, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
                    'claude-fake-weird-1': 'oops',
                ])
        def initEvent = new AgentEvent.InitEvent('fake-session-notmap-1', 'claude-fake-main-1')

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'only the well-formed entry maps'
        tokensByModel == ['claude-fake-good-1': new TokenUsage(5, 5, 0, 0)]
    }

    // NFR-R2, FR4: flat usage missing a required field degrades the whole fallback to empty
    def "degrades the fallback to empty when the flat usage is missing a required field"() {
        given: 'a synthetic result event with usage missing output_tokens'
        def resultEvent = new AgentEvent.ResultEvent(
                'fake-session-partial-usage-1', 'done',
                [input_tokens: 120, cache_creation_input_tokens: 10, cache_read_input_tokens: 5],
                null)
        def initEvent = new AgentEvent.InitEvent('fake-session-partial-usage-1', 'claude-fake-main-1')

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the fallback degrades to empty rather than a partially-filled TokenUsage, no exception'
        tokensByModel == [:]
    }

    private static BufferedReader readerOf(String scenario) {
        def resource = TokenUsageMapperSpec.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
        assert resource != null: "fixture not found for scenario '${scenario}'"
        new BufferedReader(new InputStreamReader(resource.openStream(), 'UTF-8'))
    }
}
