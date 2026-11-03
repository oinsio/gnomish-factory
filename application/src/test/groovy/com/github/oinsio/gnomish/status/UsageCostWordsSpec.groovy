package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import spock.lang.Specification

/**
 * FR20, UX4 of define-executor-contract (design D15): the one spelling of a snapshot's cost, shared
 * by {@code gnomish usage --markdown} and the {@code gnomish status} text — the priced sum, and a
 * stated lower bound naming what is missing when the cost is incomplete.
 */
class UsageCostWordsSpec extends Specification {

    def "FR20: a complete cost is the priced sum with no qualifier"() {
        expect:
        UsageCostWords.cost(new UsageCost(new BigDecimal('1'), [:], true)) == '$1.00'
    }

    def "UX4: an unpriced figure makes the cost 'at least' the priced sum, naming the unpriced tokens of every role and model"() {
        given:
        def unpriced = [(ExecutorRole.VERDICT): ['j': new TokenUsage(1, 2, 3, 4)],
            (ExecutorRole.TRANSFORM): ['m': new TokenUsage(0, 0, 0, 1)]]

        expect:
        UsageCostWords.cost(new UsageCost(new BigDecimal('2.5'), unpriced, false)) ==
                'at least $2.50; cost incomplete: 11 tokens unpriced'
    }

    def "FR20: an unreported round makes the cost a lower bound with nothing unpriced"() {
        expect:
        UsageCostWords.cost(new UsageCost(BigDecimal.ZERO, [:], false)) == 'at least $0.0000; cost incomplete: usage not reported'
    }

    def "FR20: dollars to the cent from one dollar up, to a hundredth of a cent below: #amount -> #shown"() {
        expect:
        UsageCostWords.dollars(new BigDecimal(amount)) == shown

        where:
        amount || shown
        '1' || '1.00'
        '12.345' || '12.35'
        '0.99995' || '1.0000'
        '0.00125' || '0.0013'
    }
}
