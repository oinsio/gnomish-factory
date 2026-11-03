package com.github.oinsio.gnomish.usage.wire

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.executorcontract.ModelUsage
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageCostDto
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageSnapshotDto
import spock.lang.Specification

/**
 * FR16, FR17 of define-executor-contract: the one snapshot mapping of the three record documents
 * round-trips a task's usage snapshot, spells every role by its contract token in role order and
 * every model in model-id order, writes the counts without a cost, and refuses a role key outside
 * the contract's set (design D10, D15).
 */
class UsageSnapshotWireSpec extends Specification {

    static final TokenUsage A = new TokenUsage(1, 2, 3, 4)
    static final RoundToken TOKEN = RoundToken.of('9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b')

    def "FR17: a snapshot reads back as itself, through #through"() {
        given:
        def snapshot = new UsageSnapshot(
                [(ExecutorRole.VERDICT): ['z': A, 'a': A], (ExecutorRole.TRANSFORM): ['m': A]],
                new UsageCost(new BigDecimal('0.1922'), [(ExecutorRole.VERDICT): ['z': A]], false),
                3,
                through)

        expect:
        UsageSnapshotWire.fromDto(UsageSnapshotWire.toDto(snapshot)) == snapshot

        where:
        through << [TOKEN, null]
    }

    def "FR16: every role is spelled by its contract token, in role order, models in id order, never priced"() {
        given:
        def snapshot = new UsageSnapshot(
                [(ExecutorRole.EFFECT): ['m': A], (ExecutorRole.DECISION): ['m': A], (ExecutorRole.VERDICT): ['z': A, 'a': A], (ExecutorRole.TRANSFORM): ['m': A]],
                new UsageCost(BigDecimal.ZERO, [(ExecutorRole.DECISION): ['m': A]], false), 1, TOKEN)

        when:
        def dto = UsageSnapshotWire.toDto(snapshot)

        then:
        dto.byRole().keySet() as List == [
            'transform',
            'verdict',
            'decision',
            'effect'
        ]
        dto.byRole()['verdict'].keySet() as List == ['a', 'z']
        dto.byRole().values()*.values().flatten()*.costUsd().every {
            it == null
        }
        dto.byRole()['transform']['m'] == new ModelUsage(1, 2, 3, 4, null)
        dto.cost() == new UsageCostDto(BigDecimal.ZERO, ['decision': ['m': new ModelUsage(1, 2, 3, 4, null)]], false)
        dto.rounds() == 1
        dto.through() == TOKEN.commit()
    }

    def "FR17: a recorded role key outside the contract's set is refused"() {
        when:
        UsageSnapshotWire.fromDto(new UsageSnapshotDto(['oracle': ['m': new ModelUsage(1, 2, 3, 4, null)]],
        new UsageCostDto(BigDecimal.ZERO, [:], true), 1, null))

        then:
        thrown(IllegalArgumentException)
    }
}
