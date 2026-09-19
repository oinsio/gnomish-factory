package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentScenarioReader
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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
        def events = parser.parse(FakeAgentScenarioReader.readerOf('plain-round'))
        def resultEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.ResultEvent
        } as AgentEvent.ResultEvent
        def initEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.InitEvent
        } as AgentEvent.InitEvent

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'modelUsage is authoritative, keyed by the resolved model id'
        tokensByModel == ['claude-fake-main-1': new TokenUsage(120, 45, 10, 5)]
    }

    // FR5, D4: modelUsage present with TWO model keys — proves modelUsage (not the flat usage sum) is authoritative
    def "maps the subagent-round fixture's modelUsage to a two-entry tokensByModel, not the flat usage sum"() {
        given: 'the subagent-round fixture parsed into events'
        def events = parser.parse(FakeAgentScenarioReader.readerOf('subagent-round'))
        def resultEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.ResultEvent
        } as AgentEvent.ResultEvent
        def initEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.InitEvent
        } as AgentEvent.InitEvent

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
        def events = parser.parse(FakeAgentScenarioReader.readerOf('judge-verdict-pass'))
        def resultEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.ResultEvent
        } as AgentEvent.ResultEvent
        def initEvent = events.collect {
            it.event()
        }.find {
            it instanceof AgentEvent.InitEvent
        } as AgentEvent.InitEvent

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the single modelUsage entry is mapped'
        tokensByModel == ['claude-fake-judge-1': new TokenUsage(150, 25, 0, 0)]
    }

    // FR5, D4: modelUsage entirely absent (older CLI) — fall back to flat usage keyed by the init event's model
    def "falls back to the flat usage object keyed by the init event's model when modelUsage is absent"() {
        given: 'a synthetic result event with usage present but modelUsage null (key omitted from the wire)'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-fallback-1'), 'success', UntrustedText.agent('done'), [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5], null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-fallback-1'), UntrustedText.agent('claude-fake-legacy-1'))

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the flat usage is keyed by the init event\'s main model'
        tokensByModel == ['claude-fake-legacy-1': new TokenUsage(120, 45, 10, 5)]
    }

    // NFR-R2, FR4: neither modelUsage nor usage present/interpretable — degrade to empty map, never throw
    def "degrades to an empty tokensByModel when neither modelUsage nor usage is present"() {
        given: 'a synthetic result event with neither usage nor modelUsage'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-empty-1'), 'success', UntrustedText.agent('done'), null, null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-empty-1'), UntrustedText.agent('claude-fake-main-1'))

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the map degrades to empty, no exception'
        tokensByModel == [:]
    }

    // FR5 of harden-logging-observability: a round whose whole usage extraction comes back empty
    // has lost its cost to the budget and the summary, and reads as "unreported" — the same as a
    // round that genuinely spent nothing. The per-entry skips stay DEBUG; this one is a WARN.
    def "FR5: an extraction that yields nothing at all warns that the round's cost is unreported"() {
        given:
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-empty-2'), 'success', UntrustedText.agent('done'), null, null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-empty-2'), UntrustedText.agent('claude-fake-main-1'))
        def logs = LogCaptureSupport.attach(TokenUsageMapper, Level.DEBUG)

        when:
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        tokensByModel == [:]

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.TOKEN_USAGE_UNREPORTED.head())
        warnings[0].formattedMessage.contains('no usable token usage')
    }

    // FR5: a round that DID report usage says nothing — a healthy round produces no console output.
    def "FR5: a round that reported usage warns about nothing"() {
        given:
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-ok-1'), 'success', UntrustedText.agent('done'), [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5], null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-ok-1'), UntrustedText.agent('claude-fake-main-1'))
        def logs = LogCaptureSupport.attach(TokenUsageMapper, Level.DEBUG)

        when:
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        !tokensByModel.isEmpty()
        events.isEmpty()
    }

    // NFR-R2, FR4: modelUsage absent, usage present, but no init event available to key the fallback
    def "degrades to an empty tokensByModel when the fallback usage cannot be keyed (no init event)"() {
        given: 'a synthetic result event with a usable flat usage but no init event supplied'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-no-init-1'), 'success', UntrustedText.agent('done'), [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5], null)

        when: 'tokens are mapped with a null init event'
        def tokensByModel = mapper.toTokensByModel(resultEvent, null)

        then: 'the map degrades to empty rather than guessing a key, no exception'
        tokensByModel == [:]
    }

    // NFR-R2, FR4: a malformed modelUsage entry (missing a field) is skipped per-entry, siblings still map
    def "skips a malformed modelUsage entry but keeps mapping the other well-formed entries"() {
        given: 'a synthetic result event where one model entry is missing a required field'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-partial-1'), 'success', UntrustedText.agent('done'), null, [
            'claude-fake-good-1': [inputTokens: 100, outputTokens: 20, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
            'claude-fake-bad-1' : [inputTokens: 100, outputTokens: 'not-a-number', cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
        ])
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-partial-1'), UntrustedText.agent('claude-fake-main-1'))

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the well-formed entry maps, the malformed one is skipped, no exception'
        tokensByModel == ['claude-fake-good-1': new TokenUsage(100, 20, 0, 0)]
    }

    // NFR-R2, FR4: a modelUsage entry whose value is not even a map degrades that entry only
    def "skips a modelUsage entry that is not a map at all"() {
        given: 'a synthetic result event with one entry a plain string instead of a map'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-notmap-1'), 'success', UntrustedText.agent('done'), null, [
            'claude-fake-good-1': [inputTokens: 5, outputTokens: 5, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
            'claude-fake-weird-1': 'oops',
        ])
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-notmap-1'), UntrustedText.agent('claude-fake-main-1'))

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'only the well-formed entry maps'
        tokensByModel == ['claude-fake-good-1': new TokenUsage(5, 5, 0, 0)]
    }

    // NFR-R2, FR4: flat usage missing a required field degrades the whole fallback to empty
    def "degrades the fallback to empty when the flat usage is missing a required field"() {
        given: 'a synthetic result event with usage missing output_tokens'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-partial-usage-1'), 'success', UntrustedText.agent('done'), [input_tokens: 120, cache_creation_input_tokens: 10, cache_read_input_tokens: 5], null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-partial-usage-1'), UntrustedText.agent('claude-fake-main-1'))

        when: 'tokens are mapped'
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the fallback degrades to empty rather than a partially-filled TokenUsage, no exception'
        tokensByModel == [:]
    }

    // FR10, design D11 of type-untrusted-text: the keys of this map reach state.json and the
    //     dashboard, so a hostile model id must never travel as it arrived — on either path.
    def "FR10: a hostile model id is held to ModelIdSyntax on the modelUsage path"() {
        given: 'a result event whose modelUsage names a model with an escape sequence in it'
        def hostile = "claude-x\u001B[2J\n2026-01-01 ERROR forged"
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-hostile-1'), 'success', UntrustedText.agent('done'), null, [
            (hostile): [inputTokens: 7, outputTokens: 3, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
        ])
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-hostile-1'), UntrustedText.agent('claude-fake-main-1'))

        when:
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the tokens are still reported, under the placeholder rather than under the escape'
        tokensByModel == [(ModelIdSyntax.UNUSABLE): new TokenUsage(7, 3, 0, 0)]
    }

    // FR10: and the same on the flat-usage fallback, which keys on the init event's model instead
    def "FR10: a hostile init model is held to ModelIdSyntax on the flat-usage fallback"() {
        given: 'an init event whose model carries a line break and a forged record'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-hostile-2'), 'success', UntrustedText.agent('done'),
                [input_tokens: 120, output_tokens: 20, cache_creation_input_tokens: 10, cache_read_input_tokens: 5], null)
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-hostile-2'),
                UntrustedText.agent("claude-x\n2026-01-01 ERROR forged"))

        when:
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then:
        tokensByModel == [(ModelIdSyntax.UNUSABLE): new TokenUsage(120, 20, 10, 5)]
    }

    // FR10, NFR-R2: ModelIdSyntax is many-to-one, so two refused ids land on one key. The round's
    //     cost must survive that collision — a put would drop the first entry's tokens while the
    //     map stayed non-empty, so the empty-map WARN would not fire and nothing would report it.
    def "FR10: two refused model ids sum onto the placeholder rather than overwriting"() {
        given: 'a modelUsage naming two distinct ids the syntax gate refuses, plus one it accepts'
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-collide'), 'success', UntrustedText.agent('done'), null, [
            ("claude-a[2J"): [inputTokens: 7, outputTokens: 3, cacheCreationInputTokens: 1, cacheReadInputTokens: 2],
            ("claude-b\nforged"): [inputTokens: 10, outputTokens: 5, cacheCreationInputTokens: 0, cacheReadInputTokens: 4],
            ('claude-opus-4-8[1m]'): [inputTokens: 100, outputTokens: 50, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
        ])
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-collide'), UntrustedText.agent('claude-fake-main-1'))

        when:
        def tokensByModel = mapper.toTokensByModel(resultEvent, initEvent)

        then: 'the two refused entries are one key carrying the sum of both, and neither is lost'
        tokensByModel[ModelIdSyntax.UNUSABLE] == new TokenUsage(17, 8, 1, 6)

        and: 'the accepted id keeps its own entry, untouched by the collision beside it'
        tokensByModel['claude-opus-4-8[1m]'] == new TokenUsage(100, 50, 0, 0)
        tokensByModel.size() == 2
    }

    // .claude/rules/logging.md, "Best effort must still leave a trace": keying the round's tokens
    //     on a placeholder is a degraded result, and a degraded result nobody can attribute is the
    //     shape the rule exists for — the dashboard would show "(unusable model id)" with nothing
    //     anywhere saying that a substitution happened, or how long the refused id was.
    def "FR10: the substitution of a refused model id leaves a trace"() {
        given: "the mapper's own logger, watched at DEBUG"
        def logs = LogCaptureSupport.attach(TokenUsageMapper, Level.DEBUG)

        and: 'a modelUsage entry whose key the syntax gate refuses'
        def hostile = "claude-x[2J"
        def resultEvent = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-hostile-3'), 'success', UntrustedText.agent('done'), null, [
            (hostile): [inputTokens: 7, outputTokens: 3, cacheCreationInputTokens: 0, cacheReadInputTokens: 0],
        ])
        def initEvent = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-hostile-3'), UntrustedText.agent('claude-fake-main-1'))

        when:
        mapper.toTokensByModel(resultEvent, initEvent)

        then: 'one DEBUG line names the placeholder the tokens are keyed on and the refused length'
        def event = logs.list.find {
            it.formattedMessage.contains('no accepted shape')
        }
        event != null
        event.level == Level.DEBUG
        event.formattedMessage.contains(ModelIdSyntax.UNUSABLE)
        event.formattedMessage.contains(Integer.toString(hostile.length()))

        and: 'and the refused id itself never reaches the line — it is the value that failed the gate'
        !event.formattedMessage.contains('claude-x')

        cleanup:
        logs.detach()
    }
}
