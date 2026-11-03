package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import java.time.Duration
import spock.lang.Specification

/**
 * FR15 of define-executor-contract: the engine's participant entries are built from its
 * per-invocation carriers by one owner, {@link Participants} — the executor as a {@code transform}
 * entry, a judge vote as a {@code verdict} entry with its index, an empty token map as {@code
 * ABSENT} — and the interim executor share folds the {@code transform} entries only (design D14);
 * each model's cost passes into the entry as reported (FR22).
 */
class ParticipantsSpec extends Specification {

    private static final TokenUsage TOKENS = new TokenUsage(10, 20, 30, 40)
    private static final ToolUsage EDIT = new ToolUsage('Edit', 2, Duration.ofMillis(5))

    def "FR15: an executor that reported tokens is a reported transform entry carrying them unpriced"() {
        when:
        def entry = Participants.transform('acme', new ExecutorUsage(Duration.ofSeconds(3), [EDIT], Unpriced.of(['m': TOKENS])))

        then:
        entry == new ParticipantUsage(ExecutorRole.TRANSFORM, 'acme', null, UsageProvenance.REPORTED,
                Duration.ofSeconds(3), [EDIT], ['m': new ModelUsage(TOKENS, null)])
    }

    // NFR-O1 of define-executor-contract: the transform entry takes the carrier's provenance, not the map's
    def "NFR-O1: an executor that reported usage of no model is a reported entry with an empty model map"() {
        expect:
        Participants.transform('acme', new ExecutorUsage(null, [], [:], UsageProvenance.REPORTED)).provenance() ==
        UsageProvenance.REPORTED
    }

    def "FR15: an executor that reported no token is an absent entry with an empty model map, its wall time kept"() {
        when:
        def entry = Participants.transform('acme', new ExecutorUsage(Duration.ofSeconds(3), [], [:]))

        then:
        entry.provenance() == UsageProvenance.ABSENT
        entry.byModel().isEmpty()
        entry.wallTime() == Duration.ofSeconds(3)
    }

    def "FR15: a judge vote is a verdict entry with its index, #provenance for #tokens"() {
        when:
        def entry = Participants.verdict('agent-cli', 2, Unpriced.of(tokens))

        then:
        entry.role() == ExecutorRole.VERDICT
        entry.vote() == 2
        entry.provenance() == provenance
        entry.wallTime() == null
        entry.tools().isEmpty()
        entry.byModel().keySet() == tokens.keySet()

        where:
        tokens || provenance
        ['m': TOKENS] || UsageProvenance.REPORTED
        [:] || UsageProvenance.ABSENT
    }

    // FR22: the entries take each model's tokens and cost as the carrier holds them — a priced
    //     model keeps its cost, an unpriced one stays without, for the executor and a vote alike.
    def "FR22: transform and verdict entries carry each model's cost as reported, absent where none was"() {
        given:
        def byModel = ['priced': new ModelUsage(TOKENS, new BigDecimal('0.42')), 'unpriced': new ModelUsage(TOKENS, null)]

        expect:
        Participants.transform('acme', new ExecutorUsage(null, [], byModel)).byModel() == byModel
        Participants.verdict('agent-cli', 0, byModel).byModel() == byModel
    }

    def "the type word of #type is '#word', and judge votes run on the agent CLI's"() {
        expect:
        Participants.typeWord(type) == word
        Participants.JUDGE_EXECUTOR == 'agent-cli'

        where:
        type || word
        ExecutorType.AGENT_CLI || 'agent-cli'
        ExecutorType.API || 'api'
    }
}
