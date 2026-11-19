package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR11, FR12 of migrate-verify-to-executor-contract (design D11 as amended 2026-11-14): the kit's
 * pending cases poll from a working copy other than the one {@code start} ran in, at
 * {@code <scratch>/poll/<case>}, through a second {@code ProgramExecutor} sharing the case's table of
 * pending runs — against real candidate processes: the stand-in reference executor (first live role
 * {@code transform}), its answers under a verdict-only describe with poll, and the fake that
 * keeps its pending run in its working copy's {@code .git}. No feature rests on a scheduling order:
 * each start and poll is waited to its end before the next.
 */
class ConformanceKitPollCopySpec extends ConformanceKitProgramSpecBase {

    private static final List<KitCase> PENDING = [
        KitCase.PENDING_POLL,
        KitCase.PENDING_REPOLL
    ]

    private List<Map<String, Object>> of(KitCase kase, String op) {
        operations.findAll {
            it.op == op && (it.dir as Path).fileName.toString() == kase.word()
        }
    }

    // Scenario: executor/conformance-kit / Reference executor passes the pending cases from a new working copy
    def "FR11, FR12: the reference executor passes both pending cases, each poll from the case's poll copy holding start's files without .git"() {
        when:
        def report = verify(StandIn.executor('reference'), DEFER)

        then:
        PENDING.every {
            report.verdicts()[it] instanceof CaseVerdict.Passed
        }

        and: 'start ran in the case working copy, every poll in the poll copy apart from it, the re-poll included'
        PENDING.every { kase ->
            of(kase, 'start')*.dir == [
                kitScratch.resolve(kase.word())
            ]
            && of(kase, 'poll')*.dir.toSet() == [
                kitScratch.resolve('poll').resolve(kase.word())
            ] as Set
        }
        of(KitCase.PENDING_REPOLL, 'poll').size() == 2

        and: 'a transform run polls from start\'s files without its .git; start\'s copy keeps its repository'
        PENDING.every { kase ->
            !Files.exists(kitScratch.resolve('poll').resolve(kase.word()).resolve('.git'))
            && Files.isDirectory(kitScratch.resolve(kase.word()).resolve('.git'))
        }
    }

    // Scenario: executor/wire-contract / A poll from another working copy reaches the run
    def "FR11: a poll naming another working copy reaches the run start began, and the program does not refuse its key"() {
        when:
        def report = verify(StandIn.executor('reference'), DEFER)

        then: 'the one poll of the pending case carried the key start returned, from the poll copy, and ended the run'
        of(KitCase.PENDING_POLL, 'poll').collect {
            [it.dir, it.key]
        } == [
            [
                kitScratch.resolve('poll').resolve('pending-poll'),
                'reference-deferred'
            ]
        ]
        report.lines().contains('pass pending-poll')
    }

    // FR11, FR12 (D11 as amended 2026-11-14): a program whose first live role is verdict polls from a
    //     working copy holding the content before start — for a program, a new git repository — so the
    //     working-copy check of the poll that ends the run compares two clean copies and passes.
    def "FR11, FR12: a verdict program polls from a new git repository, and both pending cases pass"() {
        when:
        def report = verify(StandIn.executor('verdict-deferring'), DEFER)

        then:
        report.failures() == 0
        PENDING.every {
            report.verdicts()[it] instanceof CaseVerdict.Passed
        }
        PENDING.every { kase ->
            Files.isDirectory(kitScratch.resolve('poll').resolve(kase.word()).resolve('.git'))
        }
        of(KitCase.PENDING_POLL, 'poll')*.dir == [
            kitScratch.resolve('poll').resolve('pending-poll')
        ]
    }

    // Scenario: executor/conformance-kit / A candidate that keeps its run in its working copy fails
    def "FR11, FR12: the fake keeping its pending run in its working copy's .git fails the pending case, naming the key and the refused poll"() {
        when:
        def report = verify(StandIn.executor('pending-in-copy'), DEFER)
        def detail = (report.verdicts()[KitCase.PENDING_POLL] as CaseVerdict.Failed).detail()

        then:
        detail.startsWith('answered failed/infrastructure: ')
        detail.contains("poll refused: no pending run has the key 'reference-deferred'")

        and: 'start recorded its run in its own copy, which the poll copy does not carry'
        Files.exists(kitScratch.resolve('pending-poll').resolve('.git').resolve('gnomish-pending-run'))
        !Files.exists(kitScratch.resolve('poll').resolve('pending-poll').resolve('.git'))

        and: 'every case that polls nothing passes'
        report.verdicts().findAll { !(it.key in PENDING) }.every {
            it.value instanceof CaseVerdict.Passed
        }
    }

    // FR12 (D11 as amended 2026-11-14): the poll runs in an observed environment of its own, and the
    //     case's verdict joins what both observations judged — a breach of the poll's result fails it.
    def "FR12: a poll whose result breaks its schema fails the pending case on the breach the poll copy's observation judged"() {
        when:
        def report = verify(StandIn.executor('poll-breaks'), DEFER)

        then:
        (report.verdicts()[KitCase.PENDING_POLL] as CaseVerdict.Failed).detail()
                .contains('the result document breaks its schema at answer (oneOf)')
        of(KitCase.PENDING_POLL, 'poll')*.dir == [
            kitScratch.resolve('poll').resolve('pending-poll')
        ]
    }
}
