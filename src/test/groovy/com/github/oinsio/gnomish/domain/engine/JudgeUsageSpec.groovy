package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * JudgeUsage: the per-vote token usage of a judge check — one {@link TokenUsage}
 * per cast vote, in vote order. The list may be empty when no judge check ran or
 * the adapter reported no tokens (design D5, NFR-C1). Implements FR13, NFR-C1 of
 * add-stage-engine.
 */
class JudgeUsageSpec extends Specification {

    // NFR-C1: judge usage exposes its per-vote token list as constructed, in order
    def "exposes the per-vote token list as constructed, in vote order"() {
        given: 'one token usage per cast vote'
        def perVote = [
            new TokenUsage(100L, 20L),
            new TokenUsage(110L, 25L),
            new TokenUsage(90L, 15L),
        ]

        when: 'a judge usage is created'
        def usage = new JudgeUsage(perVote)

        then: 'the per-vote list is exposed exactly as constructed'
        usage.perVote() == perVote
    }

    // NFR-C1: perVote is copied on construction — later source mutation cannot leak in
    def "the perVote list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new TokenUsage(100L, 20L)
        ]

        when: 'a judge usage is created and the source is then mutated'
        def usage = new JudgeUsage(source)
        source.add(new TokenUsage(999L, 999L))

        then: 'the usage keeps its original single vote'
        usage.perVote().size() == 1
        usage.perVote()[0] == new TokenUsage(100L, 20L)
    }

    // NFR-C1: the exposed perVote list is unmodifiable — no one edits it in place
    def "the exposed perVote list is unmodifiable"() {
        given: 'a judge usage'
        def usage = new JudgeUsage([
            new TokenUsage(100L, 20L)
        ])

        when: 'a caller tries to add a vote'
        usage.perVote().add(new TokenUsage(1L, 1L))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // NFR-C1: no judge ran or tokens unreported — an empty list is valid
    def "accepts an empty perVote list"() {
        when: 'a judge usage is created with no votes'
        def usage = new JudgeUsage([])

        then: 'the per-vote list is empty'
        usage.perVote().isEmpty()
    }

    // NFR-C1: none() expresses "no judge tokens" — an empty per-vote list
    def "none() yields an empty perVote list"() {
        when: 'the none() factory is used'
        def usage = JudgeUsage.none()

        then: 'the per-vote list is empty'
        usage.perVote().isEmpty()
    }

    // NFR-C1: JudgeUsage is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two usages built from equal per-vote lists are equal'
        new JudgeUsage([new TokenUsage(1L, 2L)]) == new JudgeUsage([new TokenUsage(1L, 2L)])

        and: 'a differing vote makes them unequal'
        new JudgeUsage([new TokenUsage(1L, 2L)]) != new JudgeUsage([new TokenUsage(1L, 3L)])
    }
}
