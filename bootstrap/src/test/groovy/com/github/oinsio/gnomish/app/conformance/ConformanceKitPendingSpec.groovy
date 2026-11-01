package com.github.oinsio.gnomish.app.conformance

import static com.github.oinsio.gnomish.app.conformance.KitSubjectDouble.*

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Status
import java.time.Duration
import spock.lang.Specification

/**
 * FR12, FR23 of define-executor-contract (design D18): the pending-then-poll case on one executor
 * instance — a start under the pending settings must go pending, each poll carries the key the last
 * answer returned, a wait of {@link ConformanceKit#POLL_INTERVAL} separates two polls, and a run
 * still pending after {@link ConformanceKit#POLL_LIMIT} polls fails.
 */
class ConformanceKitPendingSpec extends Specification {

    private final List<Duration> slept = []

    private CaseVerdict pending(List<Closure> answers) {
        def queue = new ArrayDeque<Closure>(answers)
        def subject = new KitSubjectDouble(answer: { r, k ->
            r.settings().path('defer').asBoolean() ? queue.poll().call(k) : conforming(r)
        })
        subject.metaClass.polledKeys = {
            -> subject.asked.findAll {
                it.op == 'poll'
            }*.key*.value()
        }
        last = subject
        new ConformanceKit([defer: true], { Duration d ->
            slept << d
        } as Sleeper).run(subject).verdicts()[KitCase.PENDING_POLL]
    }

    private KitSubjectDouble last

    def "FR23: a run that is still pending at the first poll is polled again after one wait, with the newest key"() {
        when:
        def verdict = pending([
            { k ->
                result(Status.PENDING, null, 'k1')
            },
            { k ->
                result(Status.PENDING, null, 'k2')
            },
            { k -> result(Status.COMPLETED) }
        ])

        then:
        verdict instanceof CaseVerdict.Passed
        last.polledKeys() == ['k1', 'k2']
        slept == [ConformanceKit.POLL_INTERVAL]
    }

    def "FR23: a run still pending after the poll limit fails the case"() {
        when:
        def verdict = pending((0..ConformanceKit.POLL_LIMIT).collect { n ->
            { k ->
                result(Status.PENDING, null, "k$n")
            }
        })

        then:
        verdict.line(KitCase.PENDING_POLL) == 'FAIL pending-poll: answered pending to a request that asked for no pending run'
        last.polledKeys().size() == ConformanceKit.POLL_LIMIT
        slept.size() == ConformanceKit.POLL_LIMIT - 1
        ConformanceKit.POLL_LIMIT == 5 && ConformanceKit.POLL_INTERVAL == Duration.ofSeconds(1)
    }

    def "FR12: a start under the pending settings that does not go pending fails the case without a poll"() {
        when:
        def verdict = pending([
            { k -> result(Status.COMPLETED) }
        ])

        then:
        verdict.line(KitCase.PENDING_POLL) == 'FAIL pending-poll: a start under the pending settings answered completed instead of pending'
        last.polledKeys() == []
    }

    def "FR23: the poll that ends the run is judged as a start would be"() {
        when:
        def verdict = pending([
            { k ->
                result(Status.PENDING, null, 'k1')
            },
            { k ->
                failed('lost it')
            }
        ])

        then:
        verdict.line(KitCase.PENDING_POLL) == 'FAIL pending-poll: answered failed/infrastructure: lost it'
        slept == []
    }
}
