package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * TokenUsage: four token counts (input, output, cache-creation, cache-read) for
 * one accounting unit — an executor round or a single judge vote, keyed under a
 * resolved model id by the containing type. Contract: all four counts are
 * non-negative; the type always holds all four numbers, presence/absence of a
 * whole model entry being the containing type's concern (empty map = unreported).
 * Implements FR5, D4 of add-agent-executor.
 */
class TokenUsageSpec extends Specification {

    // FR5, D4: token usage exposes all four counts as constructed
    def "exposes input, output, cacheCreation and cacheRead token counts as constructed"() {
        when: 'a token usage is created'
        def usage = new TokenUsage(1_200L, 340L, 50L, 900L)

        then: 'all four counts are exposed exactly as constructed'
        usage.input() == 1_200L
        usage.output() == 340L
        usage.cacheCreation() == 50L
        usage.cacheRead() == 900L
    }

    // FR5, D4: each validated token count round-trips its own distinct non-zero literal
    // (pins the shared requireNonNegative return against a return-value mutation, per field)
    def "validated token counts round-trip their distinct constructed literals"() {
        given: 'a usage built with four distinct non-zero counts'
        def usage = new TokenUsage(11L, 22L, 33L, 44L)

        expect: 'each accessor returns its own exact literal, not a shared zero'
        usage.input() == 11L
        usage.output() == 22L
        usage.cacheCreation() == 33L
        usage.cacheRead() == 44L
    }

    // FR5: zero tokens are valid — an adapter may report a zero-token round
    def "accepts zero token counts"() {
        when: 'a token usage is created with zero counts'
        def usage = new TokenUsage(0L, 0L, 0L, 0L)

        then: 'the zero counts are exposed as constructed'
        usage.input() == 0L
        usage.output() == 0L
        usage.cacheCreation() == 0L
        usage.cacheRead() == 0L
    }

    // FR5: a token count cannot be negative — a negative count is rejected, per field
    def "rejects a negative #component count with the component named"() {
        when: 'a token usage is created with a negative count'
        new TokenUsage(input, output, cacheCreation, cacheRead)

        then: 'construction fails and the message names the offending component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("TokenUsage.${component}")

        where:
        component        | input | output | cacheCreation | cacheRead
        'input'           | -1L   | 0L     | 0L            | 0L
        'input'           | -99L  | 5L     | 0L            | 0L
        'output'          | 0L    | -1L    | 0L            | 0L
        'output'          | 10L   | -50L   | 0L            | 0L
        'cacheCreation'   | 0L    | 0L     | -1L           | 0L
        'cacheCreation'   | 5L    | 5L     | -20L          | 0L
        'cacheRead'       | 0L    | 0L     | 0L            | -1L
        'cacheRead'       | 5L    | 5L     | 5L            | -30L
    }

    // FR5: TokenUsage is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two usages built from equal counts are equal'
        new TokenUsage(10L, 20L, 30L, 40L) == new TokenUsage(10L, 20L, 30L, 40L)

        and: 'a differing count makes them unequal'
        new TokenUsage(10L, 20L, 30L, 40L) != new TokenUsage(10L, 20L, 30L, 41L)
    }

    // FR5, D4: plus sums all four counts field-wise
    def "plus sums all four counts field-wise"() {
        given: 'two token usages'
        def a = new TokenUsage(10L, 20L, 30L, 40L)
        def b = new TokenUsage(1L, 2L, 3L, 4L)

        expect: 'the merged usage carries the field-wise sums'
        a.plus(b) == new TokenUsage(11L, 22L, 33L, 44L)
    }
}
