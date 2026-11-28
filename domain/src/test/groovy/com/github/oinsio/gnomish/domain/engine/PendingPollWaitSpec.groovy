package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.time.Duration

/**
 * FR2, NFR-R2 of migrate-verify-to-executor-contract (design D2, D19): after a pending answer the
 * next poll comes no sooner than {@code max(interval, pollAfter)}; a hint reaching past the deadline
 * ends the wait at the deadline with no further poll, and the check resolves from that answer. On
 * virtual time.
 */
class PendingPollWaitSpec extends PendingPollSpecBase {

    // Scenario: executor/wire-contract / Wait hint delays the next poll
    def "FR2: a hint of #hint against a 30 s interval waits #waited before the next poll"() {
        given:
        def ci = check()
        def executor = new ScriptedExecutor([
            pending('k-1', hint),
            passed()
        ])

        when:
        def answer = checkRounds(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer()

        then:
        answer == new VerdictAnswer.Sarif([], null)
        sleeper.slept == [waited]
        turns(executor) == [null, [ISSUED, 'k-1']]

        where:
        hint || waited
        Duration.ofMinutes(2) || Duration.ofMinutes(2)
        Duration.ofSeconds(10) || INTERVAL
        INTERVAL || INTERVAL
        null || INTERVAL
    }

    // Scenario: executor/wire-contract / Wait hint past the deadline ends the wait
    def "FR2: a hint reaching past the deadline ends the wait there, with no further poll, resolved from that answer"() {
        given: 'a first poll 30 s in, then a hint one second past the deadline'
        def ci = check()
        def executor = new ScriptedExecutor([
            pending('k-1'),
            pending('k-1', TIMEOUT.minus(INTERVAL).plusSeconds(1), PendingState.RUNNING, "waiting on 'ci' (running)")
        ])

        when:
        def answer = checkRounds(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer()

        then: 'the wait ends at the deadline, and no poll follows the hint'
        turns(executor) == [null, [ISSUED, 'k-1']]
        sleeper.slept == [
            INTERVAL,
            TIMEOUT.minus(INTERVAL)
        ]

        and: 'the timeout is the check declared class, from that answer'
        answer == new VerdictAnswer.QualityFailure([
            new Finding("external check 'ci' did not complete within PT10M", null, "waiting on 'ci' (running)")
        ])
    }

    def "FR2: a hint reaching exactly to the deadline is waited, and the poll there is the last"() {
        given:
        def ci = check()
        def executor = new ScriptedExecutor([
            pending('k-1', TIMEOUT),
            pending('k-1')
        ])

        when:
        def answer = checkRounds(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer()

        then:
        sleeper.slept == [TIMEOUT]
        turns(executor) == [null, [ISSUED, 'k-1']]
        answer instanceof VerdictAnswer.QualityFailure
    }

    def "FR2: with no hint the interval is waited until no time remains, then the timeout resolves"() {
        given:
        def ci = check('ci', com.github.oinsio.gnomish.domain.pipeline.VerifyCheck.TimeoutClass.QUALITY, Duration.ofMinutes(4))
        def executor = new ScriptedExecutor((1..4).collect { pending('k-1') })

        when:
        def answer = checkRounds(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer()

        then: 'polls at 0, 4, 8 and 12 minutes; the last one past the 10-minute deadline ends the wait'
        sleeper.slept == [Duration.ofMinutes(4)] * 3
        executor.requests.size() == 4
        answer instanceof VerdictAnswer.QualityFailure
    }

    def "FR2: a terminal answer after a wait is the check's answer, whatever its kind"() {
        given:
        def ci = check()
        def failed = new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY,
                com.github.oinsio.gnomish.untrustedtext.UntrustedText.subprocess('red'), [
                    new Finding('red', null, null)
                ], null)
        def executor = new ScriptedExecutor([
            pending('k-1'),
            result(failed)
        ])

        expect:
        checkRounds(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer() == new VerdictAnswer.QualityFailure([
            new Finding('red', null, null)
        ])
    }
}
