package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * TokenUsage: input/output token counts for one accounting unit — an executor
 * round or a single judge vote. Contract: both counts are non-negative; the type
 * always holds both numbers, presence/absence being the containing type's concern.
 * Implements FR13, NFR-C1 of add-stage-engine.
 */
class TokenUsageSpec extends Specification {

    // NFR-C1: token usage exposes input and output counts as constructed
    def "exposes input and output token counts as constructed"() {
        when: 'a token usage is created'
        def usage = new TokenUsage(1_200L, 340L)

        then: 'both counts are exposed exactly as constructed'
        usage.inputTokens() == 1_200L
        usage.outputTokens() == 340L
    }

    // NFR-C1: each validated token count round-trips its own distinct non-zero literal
    // (pins the shared requireNonNegative return against a return-value mutation, per field)
    def "validated token counts round-trip their distinct constructed literals"() {
        given: 'a usage built with two distinct non-zero counts'
        def usage = new TokenUsage(11L, 22L)

        expect: 'each accessor returns its own exact literal, not a shared zero'
        usage.inputTokens() == 11L
        usage.outputTokens() == 22L
    }

    // NFR-C1: zero tokens are valid — an adapter may report a zero-token round
    def "accepts zero token counts"() {
        when: 'a token usage is created with zero counts'
        def usage = new TokenUsage(0L, 0L)

        then: 'the zero counts are exposed as constructed'
        usage.inputTokens() == 0L
        usage.outputTokens() == 0L
    }

    // NFR-C1: a token count cannot be negative — a negative count is rejected
    def "rejects a negative #component count with the component named"() {
        when: 'a token usage is created with a negative count'
        new TokenUsage(input, output)

        then: 'construction fails and the message names the offending component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("TokenUsage.${component}")

        where:
        component      | input | output
        'inputTokens'  | -1L   | 0L
        'inputTokens'  | -99L  | 5L
        'outputTokens' | 0L    | -1L
        'outputTokens' | 10L   | -50L
    }

    // NFR-C1: TokenUsage is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two usages built from equal counts are equal'
        new TokenUsage(10L, 20L) == new TokenUsage(10L, 20L)

        and: 'a differing count makes them unequal'
        new TokenUsage(10L, 20L) != new TokenUsage(10L, 21L)
    }
}
