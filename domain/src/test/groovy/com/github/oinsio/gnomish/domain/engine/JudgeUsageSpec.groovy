package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * JudgeUsage: the per-vote token usage of a judge check — one per-model token map
 * per cast vote, in vote order. A vote's map follows the same map-only shape as
 * {@link ExecutorUsage#tokensByModel()}: empty means unreported, never a
 * fabricated zero (design D4, NFR-C1). The {@code perVote} list itself may be
 * empty when no judge check ran. Implements FR9, NFR-C1, D4 of add-agent-executor.
 */
class JudgeUsageSpec extends Specification {

    // FR9, NFR-C1: judge usage exposes its per-vote token-map list as constructed, in order
    def "exposes the per-vote token-map list as constructed, in vote order"() {
        given: 'one per-model token map per cast vote'
        def perVote = [
            ['model-a': new TokenUsage(100L, 20L, 0L, 0L)],
            ['model-a': new TokenUsage(110L, 25L, 0L, 0L)],
            ['model-b': new TokenUsage(90L, 15L, 0L, 0L)],
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
            ['model-a': new TokenUsage(100L, 20L, 0L, 0L)]
        ]

        when: 'a judge usage is created and the source is then mutated'
        def usage = new JudgeUsage(source)
        source.add(['model-b': new TokenUsage(999L, 999L, 0L, 0L)])

        then: 'the usage keeps its original single vote'
        usage.perVote().size() == 1
        usage.perVote()[0] == ['model-a': new TokenUsage(100L, 20L, 0L, 0L)]
    }

    // NFR-C1: the exposed perVote list is unmodifiable — no one edits it in place
    def "the exposed perVote list is unmodifiable"() {
        given: 'a judge usage'
        def usage = new JudgeUsage([
            ['model-a': new TokenUsage(100L, 20L, 0L, 0L)]
        ])

        when: 'a caller tries to add a vote'
        usage.perVote().add([:])

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR9: a cast vote's own token map may be empty — the adapter reported no tokens
    def "accepts a per-vote entry that is itself an empty map, meaning that vote's tokens are unreported"() {
        when: 'a judge usage is created with one unreported vote'
        def usage = new JudgeUsage([[:]])

        then: 'the per-vote list carries one empty map rather than being dropped'
        usage.perVote().size() == 1
        usage.perVote()[0].isEmpty()
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
        new JudgeUsage([
            ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]
        ]) ==
        new JudgeUsage([
            ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]
        ])

        and: 'a differing vote makes them unequal'
        new JudgeUsage([
            ['model-a': new TokenUsage(1L, 2L, 0L, 0L)]
        ]) !=
        new JudgeUsage([
            ['model-a': new TokenUsage(1L, 3L, 0L, 0L)]
        ])
    }
}
