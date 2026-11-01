package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import java.time.Instant
import spock.lang.Specification

/**
 * FR17, NFR-O3 of define-executor-contract (design D15): {@link UsageFold} is the one fold — every
 * participant entry of a round folded into the {@link UsageSnapshot} by role and model, the cost a
 * lower bound, {@code rounds} counted and {@code through} the idempotency key — and the one owner of
 * the sums its readers display.
 */
class UsageFoldSpec extends Specification {

    static final TokenUsage A = new TokenUsage(1, 2, 3, 4)
    static final TokenUsage B = new TokenUsage(10, 20, 30, 40)

    static RoundToken token(int n) {
        RoundToken.of(String.format('%040x', n + 1))
    }

    static AttemptRecord round(int n, List<ParticipantUsage> usage, RoundToken token = token(n)) {
        AttemptRecords.identified(AttemptRecords.TASK_ID, AttemptRecords.STAGE, token, n,
                AttemptRecord.Result.PASSED, Instant.EPOCH, [], usage, [], Stop.none())
    }

    static ModelUsage priced(TokenUsage tokens, String cost) {
        new ModelUsage(tokens, new BigDecimal(cost))
    }

    // Scenario: pipeline/stage-engine / Judge votes are in the totals
    def "FR17: a round's judge votes are folded under the verdict role beside the executor"() {
        given:
        def recorded = round(0, [
            Participants.transform('agent-cli', new ExecutorUsage(null, [], ['m': priced(A, '0.5')])),
            Participants.verdict('agent-cli', 0, ['j': priced(A, '0.25')]),
            Participants.verdict('agent-cli', 1, ['j': priced(B, '0.25')])
        ])

        when:
        def snapshot = UsageFold.fold(UsageSnapshot.EMPTY, recorded)

        then:
        snapshot.byRole() == [(ExecutorRole.TRANSFORM): ['m': A], (ExecutorRole.VERDICT): ['j': new TokenUsage(11, 22, 33, 44)]]
        snapshot.cost() == new UsageCost(new BigDecimal('1.00'), [:], true)
        snapshot.rounds() == 1
        snapshot.through() == token(0)
    }

    // Scenario: pipeline/stage-engine / Re-folding the recorded round is a no-op
    def "FR17: folding the round the snapshot already names by through changes nothing"() {
        given:
        def recorded = round(0, [
            Participants.transform('agent-cli', new ExecutorUsage(null, [], ['m': priced(A, '0.5')]))
        ])
        def once = UsageFold.fold(UsageSnapshot.EMPTY, recorded)

        expect:
        UsageFold.fold(once, recorded) == once
        UsageFold.foldAll([recorded, recorded]) == once
    }

    // Scenario: pipeline/stage-engine / Missing cost makes the total a lower bound
    def "NFR-O3: an entry reporting tokens without cost makes the cost an incomplete lower bound"() {
        given:
        def recorded = round(0, [
            Participants.transform('agent-cli', new ExecutorUsage(null, [], ['m': priced(A, '0.5')])),
            Participants.verdict('agent-cli', 0, Unpriced.of(['j': B]))
        ])

        when:
        def snapshot = UsageFold.fold(UsageSnapshot.EMPTY, recorded)

        then: 'the priced sum keeps every reported cost, the unpriced tokens are named by role and model'
        snapshot.cost().priced() == new BigDecimal('0.5')
        snapshot.cost().unpricedTokens() == [(ExecutorRole.VERDICT): ['j': B]]
        !snapshot.cost().complete()

        and: 'the tokens themselves are counted all the same'
        snapshot.byRole()[ExecutorRole.VERDICT] == ['j': B]
    }

    // Scenario: pipeline/stage-engine / Per-model grain survives merging
    def "FR17: two rounds reporting overlapping models sum per role and model and union the ids"() {
        when:
        def snapshot = UsageFold.foldAll([
            round(0, [
                Participants.transform('x', new ExecutorUsage(null, [], Unpriced.of(['a': A, 'b': B])))
            ]),
            round(1, [
                Participants.transform('x', new ExecutorUsage(null, [], Unpriced.of(['a': B, 'c': A])))
            ])
        ])

        then:
        snapshot.byRole() == [(ExecutorRole.TRANSFORM): ['a': new TokenUsage(11, 22, 33, 44), 'b': B, 'c': A]]
        snapshot.cost().unpricedTokens() == snapshot.byRole()
        snapshot.rounds() == 2
        snapshot.through() == token(1)
    }

    // Scenario: pipeline/stage-engine / Unreported tokens stay distinguishable from zero
    def "NFR-O3: an ABSENT entry adds no zero-valued entry, still counts its round and leaves the cost incomplete"() {
        when:
        def snapshot = UsageFold.fold(UsageSnapshot.EMPTY, round(0, [
            Participants.transform('x', ExecutorUsage.none())
        ]))

        then:
        snapshot.byRole() == [:]
        snapshot.rounds() == 1
        snapshot.cost() == new UsageCost(BigDecimal.ZERO, [:], false)
    }

    def "FR17: a round with no token folds without a key, keeping the last token; one with no entry is refused"() {
        given:
        def keyed = UsageFold.fold(UsageSnapshot.EMPTY, round(0, [
            Participants.transform('x', new ExecutorUsage(null, [], ['m': priced(A, '1')]))
        ]))
        def inPlace = round(1, [
            Participants.transform('x', new ExecutorUsage(null, [], ['m': priced(A, '1')]))
        ], null)

        when:
        def twice = UsageFold.fold(UsageFold.fold(keyed, inPlace), inPlace)

        then:
        twice.rounds() == 3
        twice.through() == token(0)
        twice.byRole() == [(ExecutorRole.TRANSFORM): ['m': new TokenUsage(3, 6, 9, 12)]]

        when:
        UsageFold.fold(UsageSnapshot.EMPTY, round(2, []))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == "UsageFold: round 2 of stage 'build' carries no usage entry"
    }

    // Scenario: pipeline/stage-engine / No display total is stored
    def "NFR-O3: the display sums are derived from the snapshot, never stored beside it"() {
        given:
        def snapshot = UsageFold.fold(UsageSnapshot.EMPTY, round(0, [
            Participants.transform('x', new ExecutorUsage(null, [], Unpriced.of(['a': A]))),
            Participants.verdict('agent-cli', 0, Unpriced.of(['a': B, 'b': A]))
        ]))

        expect:
        !(UsageSnapshot.recordComponents + UsageCost.recordComponents)*.getName().any {
            it.toLowerCase().contains('total')
        }
        UsageFold.byModel(snapshot) == ['a': new TokenUsage(11, 22, 33, 44), 'b': A]
        UsageFold.total(snapshot) == new TokenUsage(12, 24, 36, 48)
        UsageFold.total(UsageSnapshot.EMPTY) == new TokenUsage(0, 0, 0, 0)
        UsageFold.byModel(UsageSnapshot.EMPTY) == [:]
    }

    // FR20 of define-executor-contract: the Markdown report's per-role and unpriced figures and
    //     its single-number cells are sums of the owner too
    def "FR20: a role's total, the unpriced total and a figure's count are the owner's sums"() {
        given:
        def snapshot = UsageFold.foldAll([
            round(0, [
                Participants.transform('x', new ExecutorUsage(null, [], ['a': priced(A, '0.5'), 'b': new ModelUsage(B, null)])),
                Participants.verdict('agent-cli', 0, Unpriced.of(['a': A]))
            ])
        ])

        expect:
        UsageFold.roleTotal(snapshot, ExecutorRole.TRANSFORM) == new TokenUsage(11, 22, 33, 44)
        UsageFold.roleTotal(snapshot, ExecutorRole.VERDICT) == A
        UsageFold.roleTotal(snapshot, ExecutorRole.DECISION) == new TokenUsage(0, 0, 0, 0)
        UsageFold.unpricedTotal(snapshot.cost()) == new TokenUsage(11, 22, 33, 44)
        UsageFold.unpricedTotal(UsageCost.NONE) == new TokenUsage(0, 0, 0, 0)
        UsageFold.count(new TokenUsage(1, 20, 300, 4000)) == 4321
    }

    def "FR17: per-model maps add by id, the union kept"() {
        expect:
        UsageFold.addByModel(['a': A, 'b': B], ['a': B, 'c': A]) == ['a': new TokenUsage(11, 22, 33, 44), 'b': B, 'c': A]
        UsageFold.addByModel([:], [:]) == [:]
    }

    def "FR22: one model reported twice in one report merges to #merged when the halves cost #first and #second"() {
        expect:
        UsageFold.sameModel(new ModelUsage(A, cost(first)), new ModelUsage(B, cost(second))) ==
                new ModelUsage(new TokenUsage(11, 22, 33, 44), cost(merged))

        where:
        first | second || merged
        '0.25' | '0.5' || '0.75'
        '0.25' | null || null
        null | '0.5' || null
        null | null || null
    }

    private static BigDecimal cost(String figure) {
        figure == null ? null : new BigDecimal(figure)
    }
}
