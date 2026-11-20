package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.testfixtures.standin.StandIn

/**
 * FR11, FR12 of migrate-verify-to-executor-contract (design D11): the re-poll case against real
 * candidate processes — the stand-in reference executor, which answers every poll of its key with the
 * run's answer, and the fake told to break the case, which forgets the run its first poll ended. The
 * re-poll reaches the program through the factory's own {@code ProgramExecutor}, whose run table keeps
 * the key of an ended run. No feature rests on a scheduling order: each start and poll is waited to its
 * end before the next.
 */
class ConformanceKitRepollProgramSpec extends ConformanceKitProgramSpecBase {

    // Scenario: executor/conformance-kit / Reference executor passes the re-poll case
    // Scenario: executor/wire-contract / Re-poll of an ended run returns its result again
    // FR11, FR12 of migrate-verify-to-executor-contract (D11): the re-poll goes through the factory's own
    //     ProgramExecutor, whose run table keeps the ended run's key, to the program itself
    def "FR11, FR12: the reference executor answers the re-poll of its ended run's key alike, and starts no new run"() {
        when:
        def report = verify(StandIn.executor('reference'), DEFER)

        then:
        report.verdicts()[KitCase.PENDING_REPOLL] instanceof CaseVerdict.Passed
        report.lines().contains('pass pending-repoll')

        and: 'one start and two polls of the one key reached the program for the case, the re-poll included'
        def repoll = launched.findAll { it.last() in ['start', 'poll'] }
        repoll.count { it.last() == 'start' } == KitCase.values().count {
            !(it in [
                KitCase.DESCRIBE,
                KitCase.SETTINGS_SCHEMA
            ])
        }
        repoll.count { it.last() == 'poll' } == 3
    }

    // Scenario: executor/conformance-kit / Fake that forgets an ended run fails the re-poll case
    // Scenario: executor/wire-contract / An executor that forgets an ended run is non-conforming
    // FR11, FR12 of migrate-verify-to-executor-contract (D11): the fake refuses the second poll of the key
    //     whose first poll ended its run; the factory's adapter reads that refusal as failed/infrastructure,
    //     and no start follows it
    def "FR11, FR12: the fake that forgets an ended run fails exactly the re-poll case, naming the key and the refused poll"() {
        when:
        def report = verify(StandIn.executor('break-pending-repoll'), DEFER)
        def detail = (report.verdicts()[KitCase.PENDING_REPOLL] as CaseVerdict.Failed).detail()

        then:
        failed(report) == [KitCase.PENDING_REPOLL]
        detail.startsWith("a re-poll of the ended run's key 'reference-deferred' answered failed/infrastructure: ")
        detail.endsWith(', where the poll that ended it answered completed')

        and: 'the refusal was read as a failure of the poll, never as a reason to start the run again'
        launched.count { it.last() == 'start' } == KitCase.values().count {
            !(it in [
                KitCase.DESCRIBE,
                KitCase.SETTINGS_SCHEMA
            ])
        }
    }
}
