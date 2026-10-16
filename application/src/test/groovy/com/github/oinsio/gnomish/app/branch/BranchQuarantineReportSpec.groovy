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
    // Scenario: tracker/tracker-take / The quarantine report stands without the logs
    // Scenario: tracker/tracker-take / Unsupported version is a shape, not a crash
    // Scenario: lifecycle/task-branch-contract / Unsupported envelope version is its own shape, not a parse failure
    // Scenario: lifecycle/task-branch-contract / A quarantine report stands without the logs
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
    // Scenario: tracker/tracker-take / The quarantine report stands without the logs
    // Scenario: lifecycle/task-branch-contract / A quarantine report stands without the logs
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
        report.contains('fix or remove the offending file')
        report.toLowerCase().contains('ready')
    }

    // UX2 of fix-claim-epoch-fence: the closing hint has to be TRUE for every shape that can still
    // produce a quarantine. A Bare branch carries no .gnomish-task/ files and no offending file, so
    // it is told the truth — return the task, and report the run, because the branch is healthy and
    // the routing is not.
    def "a Bare branch is not sent looking for an offending file it does not have"() {
        when:
        def report = BranchQuarantineReport.of('PROJ-1', new BranchShape.Bare(), FACTS)

        then: 'the envelope instruction is absent'
        !report.contains('fix or remove the offending file')

        and: 'and the operator is told what is actually true of this branch'
        report.contains('no .gnomish-task/ files to inspect')
        report.contains('routing defect')
        report.toLowerCase().contains('ready')
    }

    // FR10 of fix-terminal-receipt-convergence (design D13): an Unregistered branch names no file
    // either — its remedy is the read that fetches the register, and the inspection a branch still
    // Unregistered after it needs before the task returns to ready.
    def "an Unregistered branch is told to fetch its register, not to fix a file"() {
        when:
        def report = BranchQuarantineReport.of('PROJ-1', new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', 'b' * 40), FACTS)

        then: 'the diagnosis names the missing register'
        report.contains('fetched without its factory register')
        report.contains('refs/gnomish-factory/PROJ-1')

        and: 'the next step is the fetching read of this task, then an inspection if it stays Unregistered'
        report.contains('run `gnomish status PROJ-1` to fetch')
        report.contains('still Unregistered after it has no factory register at origin')
        report.toLowerCase().contains('ready')
        !report.contains('fix or remove the offending file')
    }
}
