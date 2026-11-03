package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * FR17, NFR-O3 of define-executor-contract: a {@link UsageSnapshot} and its {@link UsageCost} refuse
 * a value the fold cannot produce, and hold their maps as unmodifiable deep copies without empty
 * roles, so equal snapshots compare equal by content.
 */
class UsageSnapshotSpec extends Specification {

    static final TokenUsage A = new TokenUsage(1, 2, 3, 4)

    def "FR17: the empty snapshot knows nothing and its cost is complete"() {
        expect:
        UsageSnapshot.EMPTY == new UsageSnapshot([:], new UsageCost(BigDecimal.ZERO, [:], true), 0, null)
        UsageCost.NONE.complete()
    }

    def "FR17: negative rounds, a negative priced sum and a complete cost naming unpriced tokens are refused"() {
        when:
        build()

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == message

        where:
        build || message
        ({
            -> new UsageSnapshot([:], UsageCost.NONE, -1, null)
        }) || 'UsageSnapshot.rounds must not be negative'
        ({
            -> new UsageCost(new BigDecimal('-0.01'), [:], false)
        }) || 'UsageCost.priced must not be negative'
        ({
            -> new UsageCost(BigDecimal.ZERO, [(ExecutorRole.VERDICT): ['m': A]], true)
        }) || 'UsageCost.complete must be false while tokens are unpriced'
    }

    def "FR17: zero rounds, a zero priced sum and an incomplete cost naming nothing are accepted"() {
        expect:
        new UsageSnapshot([:], new UsageCost(BigDecimal.ZERO, [:], false), 0, null).rounds() == 0
    }

    def "FR17: the maps are deep unmodifiable copies, and a role with no model is dropped"() {
        given:
        def models = ['m': A]
        def byRole = [(ExecutorRole.TRANSFORM): models, (ExecutorRole.VERDICT): [:]]

        when:
        def snapshot = new UsageSnapshot(byRole, new UsageCost(BigDecimal.ZERO, byRole, false), 1, null)
        models.put('n', A)

        then:
        snapshot.byRole() == [(ExecutorRole.TRANSFORM): ['m': A]]
        snapshot.cost().unpricedTokens() == [(ExecutorRole.TRANSFORM): ['m': A]]

        when:
        snapshot.byRole()[ExecutorRole.TRANSFORM].put('o', A)

        then:
        thrown(UnsupportedOperationException)

        when:
        snapshot.byRole().put(ExecutorRole.DECISION, [:])

        then:
        thrown(UnsupportedOperationException)
    }
}
