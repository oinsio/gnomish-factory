package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.testfixtures.executor.InProcessExecutorDouble
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR12, FR23, FR24, M4 of define-executor-contract (design D18, D19): the conformance kit against
 * real candidate processes — the stand-in reference executor, the misbehaving fake told to break one
 * case, a candidate whose describe lists {@code verdict} alone. Each run is wrapped in the factory's
 * own {@code ProgramExecutor}, each case in a git repository of its own, every launch recorded. No
 * feature rests on a scheduling order: each start and poll is waited to its end before the next.
 */
class ConformanceKitSpec extends ConformanceKitProgramSpecBase {

    // Scenario: executor/conformance-kit / Reference executor passes every case
    // Scenario: executor/wire-contract / Older executor survives a new optional field
    def "FR12, M4: the reference executor passes all thirteen cases under its pending settings"() {
        when:
        def report = verify(StandIn.executor('reference'), DEFER)

        then:
        report.verdicts().keySet().toList() == KitCase.values().toList()
        report.verdicts().values().every { it instanceof CaseVerdict.Passed }
        report.failures() == 0
        report.lines() == KitCase.values().collect {
            "pass ${it.word()}".toString()
        }

        and: 'every case environment, the describe one and the two pending cases\' poll ones included, was disposed with its scratch area'
        scratchAreas.size() == KitCase.values().size() - 1 + 2
        scratchAreas.every { !Files.exists(it) }

        and: 'each pending case started on one program executor and polled on a second sharing its run table, each operation its own process'
        launched*.last().count { it == 'poll' } == 3
    }

    // Scenario: executor/conformance-kit / Misbehaving fake fails each case it is told to break
    def "FR12, M4: the fake told to break #kase.word fails exactly that case, naming the violation"() {
        when:
        def report = verify(StandIn.executor("break-${kase.word()}"), DEFER)

        then:
        failed(report) == [kase]
        (report.verdicts()[kase] as CaseVerdict.Failed).detail().contains(detail)

        and: 'every other case passes, or — when the factory refused the describe answer — reaches no run'
        report.verdicts().findAll { it.key != kase }.every { key, verdict ->
            verdict instanceof CaseVerdict.Passed || (kase == KitCase.SETTINGS_SCHEMA && key != KitCase.DESCRIBE
            && verdict instanceof CaseVerdict.NotApplicable)
        }

        where:
        kase | detail
        KitCase.DESCRIBE | 'the describe document breaks its schema at enforcementLevel (enum)'
        KitCase.SETTINGS_SCHEMA | 'settings schema the factory cannot use'
        KitCase.TRANSFORM | 'wrote no result'
        KitCase.VERDICT | 'completed with no answer, although role verdict needs one'
        KitCase.DECISION | "chose 'not-an-option', which is none of the options"
        KitCase.UNKNOWN_ROLE | 'answered completed in role effect'
        KitCase.OVERSIZED_OUTPUT | 'the result document breaks its schema'
        KitCase.STDERR_NOISE | 'wrote no result'
        KitCase.NON_JSON_STDOUT | 'wrote no result'
        KitCase.PENDING_POLL | 'wrote no result'
        KitCase.PENDING_REPOLL | "a re-poll of the ended run's key 'reference-deferred' answered failed/infrastructure"
        KitCase.UNKNOWN_REQUEST_FIELDS | 'answered failed/infrastructure'
        KitCase.MALFORMED_ANSWER | 'the result document breaks its schema at answer (oneOf)'
    }

    // FR2, NFR-R2 of migrate-verify-to-executor-contract (design D19): no kit case is added for the
    //     pending facts; a program's out-of-range wait hint fails the pending case through the schema
    //     every result is judged against and the reading that fails it closed.
    def "FR2: a start that goes pending with a wait hint of -1 fails the pending case, naming pollAfterMillis"() {
        when:
        def report = verify(StandIn.executor('pending-bad-hint'), DEFER)

        then:
        def verdict = report.verdicts()[KitCase.PENDING_POLL]
        verdict instanceof CaseVerdict.Failed
        (verdict as CaseVerdict.Failed).detail().contains('pollAfterMillis')
        !launched.any { it.last() == 'poll' }
    }

    // Scenario: executor/conformance-kit / A built-in executor is driven through the same cases in process
    def "FR8, D19: the in-process double gets the program's verdict on every case off the wire, #preset"() {
        given:
        def inProcess = preset == 'reference' ? new InProcessExecutorDouble() : InProcessExecutorDouble.breaking(preset - 'break-')

        when:
        def onWire = verify(StandIn.executor(preset), DEFER)
        def offWire = new ConformanceKit([:], { d -> } as Sleeper)
        .run(new BuiltInCandidate(inProcess.describe(), { KitCase kase, Path workingCopy ->
            inProcess
        } as CaseBinding, [:], Files.createTempDirectory(tempDir, 'built-in-')))

        then:
        KitCase.values().findAll { !it.wireOnly() }.every {
            onWire.verdicts()[it].getClass() == offWire.verdicts()[it].getClass()
        }
        KitCase.values().findAll {
            it.wireOnly()
        }.every {
            offWire.verdicts()[it] instanceof CaseVerdict.NotApplicable
        }

        where:
        preset << ['reference'] + InProcessExecutorDouble.BREAKABLE.collect {
            "break-${it}".toString()
        }
    }

    // Scenario: executor/conformance-kit / Undeclared role is refused by the candidate
    def "FR12: a start in a role the describe does not list passes when refused and fails when answered completed"() {
        when:
        def refused = verify(StandIn.executor('reference'))
        def answered = verify(StandIn.executor('break-unknown-role'))

        then:
        refused.verdicts()[KitCase.UNKNOWN_ROLE] instanceof CaseVerdict.Passed
        launched.count { it[-1] == 'start' } > 0
        answered.lines().contains('FAIL unknown-role: answered completed in role effect, which its describe does not list')
    }

    // Scenario: executor/conformance-kit / Role not declared is not exercised
    // Scenario: executor/conformance-kit / Pending case needs the poll capability
    // Scenario: executor/conformance-kit / Re-poll case needs the poll capability
    def "FR12, FR23: a verdict-only candidate runs the verdict case, the other roles and the pending case are not applicable, and no poll is sent"() {
        when:
        def report = verify(StandIn.executor('verdict-only'))

        then:
        report.failures() == 0
        [
            KitCase.TRANSFORM,
            KitCase.DECISION,
            KitCase.MALFORMED_ANSWER,
            KitCase.PENDING_POLL,
            KitCase.PENDING_REPOLL
        ].every {
            report.verdicts()[it] instanceof CaseVerdict.NotApplicable
        }
        report.verdicts()[KitCase.VERDICT] instanceof CaseVerdict.Passed
        report.lines().contains('n/a  pending-poll: the describe declares no poll capability')
        report.lines().contains('n/a  pending-repoll: the describe declares no poll capability')
        report.lines().contains('n/a  transform: the describe lists no role transform')

        and: 'no poll reached the candidate'
        !launched.any { it.last() == 'poll' }
    }
}
