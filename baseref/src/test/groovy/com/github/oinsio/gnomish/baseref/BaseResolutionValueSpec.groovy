package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * The value types base resolution speaks in: {@link BaseRefRequest} going in, {@link BaseDecision}
 * and {@link BaseResolution} coming out.
 *
 * <p>FR10: the policy is a function from values to a value, so each of these is complete on
 * construction and immune to a caller mutating what it was handed — a request or a refusal that
 * could change under its reader would make the decision unreproducible, and reproducibility is what
 * lets two instances resolve one task the same way.
 */
class BaseResolutionValueSpec extends Specification {

    def "the request refuses a missing non-optional input"() {
        when:
        new BaseRefRequest(null, designator, allowedBases, null, mode, null)

        then:
        thrown(NullPointerException)

        where:
        designator | allowedBases | mode
        null | AllowedBases.empty() | ResolutionMode.MANUAL
        BaseDesignator.absent() | null | ResolutionMode.MANUAL
        BaseDesignator.absent() | AllowedBases.empty() | null
    }

    def "the decision and the refusal are complete values"() {
        when:
        new BaseDecision(ref, rule, reason)

        then:
        thrown(NullPointerException)

        where:
        ref | rule | reason
        null | BaseRule.LOCAL_HEAD | 'because'
        'HEAD' | null | 'because'
        'HEAD' | BaseRule.LOCAL_HEAD | null
    }

    def "a resolved outcome always carries a decision and an underdetermined one always a cause"() {
        when:
        new BaseResolution.Resolved(null)

        then:
        thrown(NullPointerException)

        when:
        new BaseResolution.Underdetermined(null, [], 'because')

        then:
        thrown(NullPointerException)

        when:
        new BaseResolution.Underdetermined(UnderdeterminedCause.NO_DEFAULT_BRANCH, [], null)

        then:
        thrown(NullPointerException)
    }

    def "an underdetermined outcome copies the values it reports"() {
        given:
        def values = ['a']
        def outcome = new BaseResolution.Underdetermined(
                UnderdeterminedCause.DESIGNATOR_CONFLICT, values, 'because')

        when:
        values.add('b')

        then:
        outcome.values() == ['a']
    }
}
