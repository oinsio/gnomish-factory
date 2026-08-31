package com.github.oinsio.gnomish.app.branch

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.domain.branch.BranchShape
import java.time.Instant
import spock.lang.Specification

/**
 * BranchQuarantineReport: the tracker-facing text of a quarantine — what was found, how much
 * automatic recovery the task had already consumed, and what a human should do next, readable
 * without factory logs.
 *
 * FR15, NFR-O2, UX2 of harden-task-branch-contract.
 */
class BranchQuarantineReportSpec extends Specification {

    private static final AbortFacts FACTS =
    new AbortFacts(4, Instant.parse('2026-08-20T10:00:00Z'), 1)

    // NFR-O2: the report names the shape and its diagnosis — for an unsupported envelope, the
    // offending file with the observed and the supported version
    def "names the offending file with the observed and supported versions"() {
        when:
        def report = BranchQuarantineReport.of('PROJ-1', new BranchShape.UnsupportedVersion('state.json', 9, 1), FACTS)

        then:
        report.contains('PROJ-1')
        report.contains('state.json')
        report.contains('9')
        report.contains('1')
    }

    // NFR-O2: the attempts consumed are stated per category — "born unreadable" and "went
    // unreadable after four automatic attempts" are different diagnoses
    def "states the attempts consumed, split by category, and that the quarantine spent none"() {
        when:
        def report = BranchQuarantineReport.of('PROJ-1', new BranchShape.Corrupt('task.json: bad json'), FACTS)

        then: 'the total and both category shares appear'
        report.contains('4')
        report.contains('3 crashed runs')
        report.contains('1 failed branch repairs')

        and: 'the report says this quarantine consumed none of them'
        report.contains('spent none')
    }

    // UX2: the operator is told what to do next, not only what happened
    def "explains what a human should do next"() {
        when:
        def report = BranchQuarantineReport.of('PROJ-1', new BranchShape.Unknown('state without task'), FACTS)

        then:
        report.contains('state without task')
        report.contains('.gnomish-task/')
        report.toLowerCase().contains('ready')
    }
}
