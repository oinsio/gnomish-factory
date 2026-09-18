package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR2, FR6, FR9 of add-base-ref-resolution: {@link FreshClaimBaseReport#underdetermined} names the
 * offending designator values when the resolver found some, and says nothing about values when it
 * found none — the "Values named:" line is conditional on {@code values}, not always rendered.
 *
 * <p>Task 6.1 of type-untrusted-text: each value is a carrier and leaves through the comment exit,
 * so what the report shows the operator is the label inside a labeled fence rather than bare.
 */
class FreshClaimBaseReportSpec extends Specification {

    def "names the designator values found on the task, when there are any"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1',
                UnderdeterminedCause.DESIGNATOR_CONFLICT,
                [
                    UntrustedText.tracker('release/9'),
                    UntrustedText.tracker('main')
                ],
                'two values on one task')

        then:
        report.contains('Values named:')

        and: 'each one inside its own labeled fence, so a label cannot pose as a report line'
        report.count('Untrusted machine output:') == 2
        report.contains('release/9')
        report.contains('main')
    }

    def "says nothing about values when the resolver found none"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1', UnderdeterminedCause.NO_DEFAULT_BRANCH, [], 'no default branch bound')

        then:
        !report.contains('Values named:')
    }
}
