package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * FR15 of define-executor-contract: a {@link ParticipantUsage} entry and its {@link ModelUsage} hold
 * their invariants — a named executor, a vote index on a verdict entry only and never negative, no
 * negative wall time, no negative cost, an unpriced model never read as zero — and copy what they
 * are handed (design D14).
 */
class ParticipantUsageSpec extends Specification {

    private static final TokenUsage TOKENS = new TokenUsage(1, 2, 3, 4)

    private static ParticipantUsage entry(ExecutorRole role, Integer vote) {
        new ParticipantUsage(role, 'agent-cli', vote, UsageProvenance.REPORTED, Duration.ofSeconds(1), [],
        ['m': new ModelUsage(TOKENS, null)])
    }

    def "FR15: a #role entry #what"() {
        when:
        entry(role, vote)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == message

        where:
        role | vote || what | message
        ExecutorRole.VERDICT | null || 'without a vote is refused' | 'ParticipantUsage.vote is required on a verdict entry'
        ExecutorRole.TRANSFORM | 0 || 'with a vote is refused' | 'ParticipantUsage.vote is carried by a verdict entry only'
        ExecutorRole.DECISION | 1 || 'with a vote is refused' | 'ParticipantUsage.vote is carried by a verdict entry only'
        ExecutorRole.VERDICT | -1 || 'with a negative vote is refused' | 'ParticipantUsage.vote must not be negative'
    }

    def "FR15: a verdict entry carries vote 0, and every other role none"() {
        expect:
        entry(ExecutorRole.VERDICT, 0).vote() == 0
        entry(ExecutorRole.VERDICT, 0).executor() == 'agent-cli'
        entry(role, null).vote() == null

        where:
        role << [
            ExecutorRole.TRANSFORM,
            ExecutorRole.DECISION,
            ExecutorRole.EFFECT
        ]
    }

    def "FR15: a blank executor name is refused"() {
        when:
        new ParticipantUsage(ExecutorRole.TRANSFORM, ' ', null, UsageProvenance.ABSENT, null, [], [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'ParticipantUsage.executor must not be blank'
    }

    def "FR15: a negative wall time is refused, zero and unknown are kept"() {
        when:
        new ParticipantUsage(ExecutorRole.TRANSFORM, 'x', null, UsageProvenance.ABSENT, Duration.ofMillis(-1), [], [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'ParticipantUsage.wallTime must not be negative'

        expect:
        new ParticipantUsage(ExecutorRole.TRANSFORM, 'x', null, UsageProvenance.ABSENT, Duration.ZERO, [], [:])
        .wallTime() == Duration.ZERO
        new ParticipantUsage(ExecutorRole.TRANSFORM, 'x', null, UsageProvenance.ABSENT, null, [], [:]).wallTime() == null
    }

    def "FR15: a negative cost is refused; zero and absent are kept apart"() {
        when:
        new ModelUsage(TOKENS, new BigDecimal('-0.01'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'ModelUsage.costUsd must not be negative'

        expect:
        new ModelUsage(TOKENS, BigDecimal.ZERO).costUsd() == BigDecimal.ZERO
        new ModelUsage(TOKENS, null).costUsd() == null
    }

    def "FR15: the tools and the model map are copied and unmodifiable"() {
        given:
        def tools = [
            new ToolUsage('Edit', 1, Duration.ZERO)
        ]
        def models = ['m': new ModelUsage(TOKENS, null)]
        def e = new ParticipantUsage(ExecutorRole.TRANSFORM, 'x', null, UsageProvenance.REPORTED, null, tools, models)

        when:
        tools.clear()
        models.clear()

        then:
        e.tools().size() == 1
        e.byModel().size() == 1

        when:
        e.byModel().clear()

        then:
        thrown(UnsupportedOperationException)
    }
}
