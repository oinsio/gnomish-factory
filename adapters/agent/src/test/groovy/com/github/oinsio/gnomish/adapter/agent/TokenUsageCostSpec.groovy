package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR22, NG10 of define-executor-contract: {@link TokenUsageMapper} carries each {@code modelUsage}
 * entry's {@code costUSD} beside that model's four counts, takes it only as the CLI reported it
 * (the acceptance rule and its trace are {@link ReportedCost}'s, driven here through the mapper),
 * and never prices tokens itself — an absent or unusable cost stays absent, never zero, and the
 * flat-usage fallback carries none. The token mapping itself is {@code TokenUsageMapperSpec}'s.
 */
class TokenUsageCostSpec extends Specification {

    private static final TokenUsage MAIN = new TokenUsage(4099, 781, 7395, 93494)
    private static final TokenUsage SUB = new TokenUsage(530, 14, 0, 0)

    def mapper = new TokenUsageMapper()
    def init = new AgentEvent.InitEvent(UntrustedText.agent('fake-session-cost-1'), UntrustedText.agent('claude-fake-main-1'))

    // FR22: the round's usage (the extractor's ExecutorUsage, and the transform entry built from it)
    //     carries the cost under the model that reported one, and nothing under the other.
    // Scenario: executor/agent-executor / Cost rides with its model
    def "FR22: the cost rides with the model that reported it and the other model carries none"() {
        given: 'a result event reporting two model ids, only one of them with costUSD'
        def result = resultWith([
            'claude-fake-main-1': counts(MAIN) + [costUSD: 0.160717],
            'claude-fake-sub-1' : counts(SUB),
        ])

        when: "the round's result is extracted"
        def usage = new AgentRoundResultExtractor().extract([event(init), event(result)], Instant.EPOCH).usage()

        then: 'the priced model carries its cost as the CLI wrote it, the other carries no cost'
        usage.byModel() == [
            'claude-fake-main-1': new ModelUsage(MAIN, new BigDecimal('0.160717')),
            'claude-fake-sub-1' : new ModelUsage(SUB, null),
        ]

        and: "the round's transform entry carries the same per-model usage"
        Participants.transform('agent-cli', usage).byModel() == usage.byModel()
    }

    // NG10: an integral cost and a reported zero are figures the CLI wrote; they are kept exactly.
    def "NG10: an integral cost and a reported zero are kept as written"() {
        when:
        def byModel = mapper.toByModel(resultWith([
            'claude-a': counts(MAIN) + [costUSD: 2],
            'claude-b': counts(SUB) + [costUSD: 0],
        ]), init)

        then:
        byModel['claude-a'].costUsd() == new BigDecimal('2')
        byModel['claude-b'].costUsd() == BigDecimal.ZERO
    }

    // FR22: a costUSD that is not a finite, non-negative number is no cost at all — the entry's
    //     tokens still map — and the degraded value leaves a DEBUG trace naming the model.
    def "FR22: an unusable costUSD (#raw) carries no cost, keeps the tokens and leaves a trace"() {
        given:
        def logs = LogCaptureSupport.attach(ReportedCost, Level.DEBUG)

        when:
        def byModel = mapper.toByModel(resultWith(['claude-a': counts(MAIN) + [costUSD: raw]]), init)
        def lines = List.copyOf(logs.list)
        logs.detach()

        then:
        byModel == ['claude-a': new ModelUsage(MAIN, null)]
        lines.size() == 1
        lines[0].level == Level.DEBUG
        lines[0].formattedMessage.contains("'claude-a' has an unusable costUSD")

        where:
        raw << [
            '0.5',
            -0.01d,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            [0.5]
        ]
    }

    // FR22: an entry without costUSD is the CLI pricing nothing — no cost, and nothing to say.
    def "FR22: an entry without costUSD carries no cost and logs nothing"() {
        given:
        def logs = LogCaptureSupport.attach(ReportedCost, Level.DEBUG)

        when:
        def byModel = mapper.toByModel(resultWith(['claude-a': counts(MAIN)]), init)
        def lines = List.copyOf(logs.list)
        logs.detach()

        then:
        byModel == ['claude-a': new ModelUsage(MAIN, null)]
        lines.isEmpty()
    }

    // FR22: the flat usage object of an older CLI reports no cost, so its one entry is unpriced,
    //     even when the event carries a top-level total the adapter must not apportion (NG10).
    def "FR22: the flat-usage fallback carries no cost"() {
        given: 'a result event with flat usage, no modelUsage, and a top-level total cost'
        def result = new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-cost-1'), 'success', UntrustedText.agent('done'),
                [input_tokens: 120, output_tokens: 45, cache_creation_input_tokens: 10, cache_read_input_tokens: 5, total_cost_usd: 0.3], null)

        expect:
        mapper.toByModel(result, init) == ['claude-fake-main-1': new ModelUsage(new TokenUsage(120, 45, 10, 5), null)]
    }

    // FR10 of type-untrusted-text with FR22: two refused ids land on one placeholder key; the merged
    //     entry is priced only when both halves are, so it never claims a cost for unpriced tokens.
    def "FR22: two entries merged onto one key are priced only when both reported a cost"() {
        when:
        def byModel = mapper.toByModel(resultWith([
            ("claude-a\u001B[2J"): counts(new TokenUsage(7, 3, 1, 2)) + [costUSD: first],
            ("claude-b\nforged"): counts(new TokenUsage(10, 5, 0, 4)) + (second == null ? [:] : [costUSD: second]),
        ]), init)

        then:
        byModel == [(ModelIdSyntax.UNUSABLE): new ModelUsage(new TokenUsage(17, 8, 1, 6), merged)]

        where:
        first | second || merged
        0.25 | 0.5 || new BigDecimal('0.75')
        0.25 | null || null
    }

    private static AgentEvent.ResultEvent resultWith(Map<String, Object> modelUsage) {
        new AgentEvent.ResultEvent(UntrustedText.agent('fake-session-cost-1'), 'success', UntrustedText.agent('done'), null, modelUsage)
    }

    private static Map<String, Object> counts(TokenUsage tokens) {
        [inputTokens: tokens.input(), outputTokens: tokens.output(),
            cacheCreationInputTokens: tokens.cacheCreation(), cacheReadInputTokens: tokens.cacheRead()]
    }

    private static TimestampedEvent event(AgentEvent event) {
        new TimestampedEvent(event, Instant.EPOCH)
    }
}
