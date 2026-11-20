package com.github.oinsio.gnomish.app.conformance

import static com.github.oinsio.gnomish.app.conformance.KitSubjectDouble.*

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.WireDocument
import java.time.Duration
import spock.lang.Specification

/**
 * FR11, FR12 of migrate-verify-to-executor-contract (design D11): the re-poll case on the kit's core,
 * against the in-process {@link KitSubjectDouble} — start under the pending settings, poll to a
 * terminal status with the newest key, then poll the key that ended the run once more, on the same
 * executor, and pass only when that poll answers {@code completed} with the same answer.
 */
class ConformanceKitRepollSpec extends Specification {

    private KitSubjectDouble subject

    private CaseVerdict repoll(List<Closure> polls) {
        def queue = new ArrayDeque<Closure>(polls)
        subject = new KitSubjectDouble(answer: { r, k ->
            !r.workspace().path().endsWith('/pending-repoll') ? conforming(r)
            : k == null ? result(Status.PENDING, null, 'k1') : queue.poll().call(k)
        })
        new ConformanceKit([defer: true], { Duration d -> } as Sleeper).run(subject).verdicts()[KitCase.PENDING_REPOLL]
    }

    private List<String> repolled() {
        subject.asked.findAll {
            it.kase == KitCase.PENDING_REPOLL && it.op == 'poll'
        }*.key*.value()
    }

    def "FR12: a re-poll of the key that ended the run answering it alike passes, the key being the newest"() {
        when:
        def verdict = repoll([
            { k -> result(Status.PENDING, null, 'k2') },
            { k -> result(Status.COMPLETED) },
            { k -> result(Status.COMPLETED) }
        ])

        then:
        verdict instanceof CaseVerdict.Passed
        repolled() == ['k1', 'k2', 'k2']
        subject.asked.count {
            it.kase == KitCase.PENDING_REPOLL && it.op == 'start'
        } == 1
        subject.asked.find {
            it.kase == KitCase.PENDING_REPOLL
        }.request.settings().toString() == '{"defer":true}'
    }

    def "FR11, FR12: a re-poll answering #what fails the case naming the key and the second answer"() {
        when:
        def verdict = repoll([
            { k -> result(Status.COMPLETED, VERDICT) },
            second
        ])

        then:
        verdict.line(KitCase.PENDING_REPOLL) == "FAIL pending-repoll: a re-poll of the ended run's key 'k1' " + line
        repolled() == ['k1', 'k1']

        where:
        what | second || line
        'a refusal' | { k ->
            failed("no pending run has the key '${k.value()}'")
        } ||
        "answered failed/infrastructure: no pending run has the key 'k1', where the poll that ended it answered completed"
        'pending' | { k -> result(Status.PENDING, null, 'k9') } ||
        'answered pending, where the poll that ended it answered completed'
        'another answer' | { k -> result(Status.COMPLETED, SELECT_FIRST) } ||
        'answered completed with another answer than the poll that ended it'
        'no answer' | { k -> result(Status.COMPLETED) } ||
        'answered completed with another answer than the poll that ended it'
    }

    def "FR12: a run whose ending poll fails the case is judged as the pending case judges it, and not re-polled"() {
        when:
        def verdict = repoll([
            { k -> failed('lost it') }
        ])

        then:
        verdict.line(KitCase.PENDING_REPOLL) == 'FAIL pending-repoll: answered failed/infrastructure: lost it'
        repolled() == ['k1']
    }

    def "FR12: a start under the pending settings that does not go pending fails the re-poll case without a poll"() {
        given:
        subject = new KitSubjectDouble()

        when:
        def verdict = new ConformanceKit([defer: true], { Duration d -> } as Sleeper).run(subject).verdicts()[KitCase.PENDING_REPOLL]

        then:
        verdict.line(KitCase.PENDING_REPOLL) == 'FAIL pending-repoll: a start under the pending settings answered completed instead of pending'
        repolled() == []
    }

    def "FR2, FR12: a re-poll whose document broke its schema fails the case on the breach"() {
        given:
        def planted = breach(WireDocument.RESULT)
        int polls = 0
        subject = new KitSubjectDouble(answer: { r, k ->
            if (!r.workspace().path().endsWith('/pending-repoll')) {
                return conforming(r)
            }
            if (k != null && ++polls == 2) {
                subject.breaches[KitCase.PENDING_REPOLL] = [planted]
            }
            k == null ? result(Status.PENDING, null, 'k1') : result(Status.COMPLETED)
        })

        when:
        def verdict = new ConformanceKit([defer: true], { Duration d -> } as Sleeper).run(subject).verdicts()[KitCase.PENDING_REPOLL]

        then:
        verdict == new CaseVerdict.Failed(planted.detail())
        repolled() == ['k1', 'k1']
    }

    def "FR12: an executor that throws from the re-poll fails the case naming the operation"() {
        when:
        def verdict = repoll([
            { k -> result(Status.COMPLETED) },
            { k -> throw new IllegalStateException('gone') }
        ])

        then:
        verdict == new CaseVerdict.Failed(CaseJudgement.threw('Executor.poll', new IllegalStateException('gone')))
    }

    // Scenario: executor/conformance-kit / Re-poll case needs the poll capability
    def "FR12: a candidate whose describe declares no poll has the re-poll case not applicable, and no poll is sent"() {
        given:
        subject = new KitSubjectDouble(described: new KitSubject.Described(describeOf(LIVE, []), null, [], []))

        when:
        def report = new ConformanceKit([defer: true], { Duration d -> } as Sleeper).run(subject)

        then:
        report.lines().contains('n/a  pending-repoll: the describe declares no poll capability')
        !subject.asked.any { it.op == 'poll' }
        !subject.asked.any { it.kase == KitCase.PENDING_REPOLL }
    }
}
