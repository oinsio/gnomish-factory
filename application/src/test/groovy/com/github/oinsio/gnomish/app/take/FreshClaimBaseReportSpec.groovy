package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import spock.lang.Specification

/**
 * FR2, FR6, FR9 of add-base-ref-resolution: {@link FreshClaimBaseReport#underdetermined} names the
 * offending designator values when the resolver found some, and says nothing about values when it
 * found none — the "Values named:" line is conditional on {@code values}, not always rendered.
 */
class FreshClaimBaseReportSpec extends Specification {

    def "names the designator values found on the task, when there are any"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1', UnderdeterminedCause.DESIGNATOR_CONFLICT, ['release/9', 'main'], 'two values on one task')

        then:
        report.contains('Values named: release/9, main')
    }

    def "says nothing about values when the resolver found none"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1', UnderdeterminedCause.NO_DEFAULT_BRANCH, [], 'no default branch bound')

        then:
        !report.contains('Values named:')
    }
}
