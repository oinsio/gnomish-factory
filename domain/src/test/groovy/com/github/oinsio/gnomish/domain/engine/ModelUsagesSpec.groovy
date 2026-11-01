package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * FR22 of define-executor-contract: {@link ModelUsages} gives the token-only view of a per-model map;
 * merging two reports of one model is {@link UsageFold#sameModel} ({@code UsageFoldSpec}).
 */
class ModelUsagesSpec extends Specification {

    private static final TokenUsage A = new TokenUsage(1, 2, 3, 4)
    private static final TokenUsage B = new TokenUsage(10, 20, 30, 40)

    def "FR22: the token view keeps every model's counts, drops the costs and cannot be changed"() {
        when:
        def tokens = ModelUsages.tokensOf(['m': new ModelUsage(A, new BigDecimal('1')), 'n': new ModelUsage(B, null)])

        then:
        tokens == ['m': A, 'n': B]

        when:
        tokens.put('o', A)

        then:
        thrown(UnsupportedOperationException)
    }
}
