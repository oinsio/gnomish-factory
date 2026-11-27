package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

/**
 * FR2, FR4, FR9, UX1 of migrate-verify-to-executor-contract (design D19): a timed-out wait is
 * classified first by the last pending answer's state — unreported or unobserved resolve as
 * cannot-verify whatever the declared class — then by the declared class; its details are the
 * reason of the last answer that was not unobserved, followed by the last answer's reason when that
 * one was unobserved. On virtual time.
 */
class PendingPollTimeoutSpec extends PendingPollSpecBase {

    static final String MESSAGE = "external check 'ci' did not complete within PT10M"

    private VerdictAnswer timedOut(VerifyCheck.Executor ci, List<ExecutionResult> answers) {
        poll(new ScriptedExecutor(answers)).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci])).answer()
    }

    /** Answers that keep the check pending past its 10-minute deadline at a 30 s interval. */
    private static List<ExecutionResult> until(ExecutionResult last, List<ExecutionResult> before = []) {
        before + [last] * (21 - before.size())
    }

    // Scenario: verification/pending-poll / Timeout with a quality class
    def "FR2: a quality check still running at its timeout fails as a quality failure naming the timeout"() {
        when:
        def answer = timedOut(check(), until(pending('k-1', null, PendingState.RUNNING, "waiting on 'test' (running)")))

        then:
        answer == new VerdictAnswer.QualityFailure([
            new Finding(MESSAGE, null, "waiting on 'test' (running)")
        ])
        VerdictDerivation.derive(answer, Threshold.ERROR) instanceof Verdict.Fail
    }

    // Scenario: verification/pending-poll / Expected context never appeared
    def "FR4: a quality check whose expected context never appeared resolves as cannot-verify naming it"() {
        when:
        def answer = timedOut(check(), until(pending('k-1', null, PendingState.UNREPORTED, "waiting on 'lint' (absent)")))

        then:
        answer == new VerdictAnswer.InfrastructureFailure(UntrustedText.manifest(MESSAGE),
                UntrustedText.manifest("waiting on 'lint' (absent)"))
        VerdictDerivation.derive(answer, Threshold.ERROR) instanceof Verdict.CannotVerify
    }

    // Scenario: executor/wire-contract / Unreported subject at the timeout cannot verify
    def "FR9: a quality check whose last pending answer was #state resolves as cannot-verify, no attempt burned"() {
        when:
        def answer = timedOut(check(), until(pending('k-1', null, state, 'r')))

        then:
        answer == new VerdictAnswer.InfrastructureFailure(UntrustedText.manifest(MESSAGE), UntrustedText.manifest('r'))
        VerdictDerivation.derive(answer, Threshold.ERROR) instanceof Verdict.CannotVerify

        where:
        state << [
            PendingState.UNREPORTED,
            PendingState.UNOBSERVED
        ]
    }

    // The reading of an unknown token off the wire: PendingPollCheckRoundSpec (:bootstrap) carries the marker.
    def "FR9: a running last answer — the reading of an absent or unknown state token — keeps the #timeoutClass class"() {
        when:
        def answer = timedOut(check('ci', timeoutClass), until(pending('k-1')))

        then:
        answer == expected

        where:
        timeoutClass || expected
        VerifyCheck.TimeoutClass.QUALITY || new VerdictAnswer.QualityFailure([
            new Finding(MESSAGE, null, null)
        ])
        VerifyCheck.TimeoutClass.INFRASTRUCTURE || new VerdictAnswer.InfrastructureFailure(
                UntrustedText.manifest(MESSAGE), UntrustedText.factory(''))
    }

    def "FR9: only the last answer's state decides: an earlier unreported answer does not, a later running one keeps the class"() {
        when:
        def answer = timedOut(check(), until(pending('k-1', null, PendingState.RUNNING, 'now running'),
                [
                    pending('k-1', null, PendingState.UNREPORTED, 'absent')
                ]))

        then:
        answer == new VerdictAnswer.QualityFailure([
            new Finding(MESSAGE, null, 'now running')
        ])
    }

    // Scenario: verification/pending-poll / Timeout after a rate-limited read still names the missing contexts
    def "UX1: an absent context, then a rate-limited read hinting past the deadline: the cannot-verify names the context, then the rate limit"() {
        given:
        def limit = 'the SCM refused the read for its rate limit (HTTP 429); the read backs off until the check\'s next poll'

        when:
        def answer = timedOut(check(), [
            pending('k-1', null, PendingState.UNREPORTED, "waiting on 'lint' (absent)"),
            pending('k-1', Duration.ofHours(1), PendingState.UNOBSERVED, limit)
        ])

        then:
        answer == new VerdictAnswer.InfrastructureFailure(UntrustedText.manifest(MESSAGE),
                UntrustedText.factory("waiting on 'lint' (absent); then: " + limit))
        sleeper.slept == [
            INTERVAL,
            TIMEOUT.minus(INTERVAL)
        ]
    }

    def "UX1: the details of #why"() {
        when:
        def answer = timedOut(check(), answers)

        then:
        (answer as VerdictAnswer.InfrastructureFailure).details() == details

        where:
        why | answers || details
        'only unobserved answers are the last reason alone' | until(pending('k-1', null, PendingState.UNOBSERVED, 'b'),
                [
                    pending('k-1', null, PendingState.UNOBSERVED, 'a')
                ]) || UntrustedText.manifest('b')
        'an unobserved last answer with no reason are the awaited reason' | until(pending('k-1', null, PendingState.UNOBSERVED),
                [
                    pending('k-1', null, PendingState.UNREPORTED, 'absent')
                ]) || UntrustedText.manifest('absent')
        'an unobserved last answer after a reasonless one are its own reason' | until(pending('k-1', null, PendingState.UNOBSERVED, 'limit'),
                [
                    pending('k-1', null, PendingState.UNREPORTED)
                ]) || UntrustedText.manifest('limit')
        'no reason at all are empty' | until(pending('k-1', null, PendingState.UNREPORTED)) || UntrustedText.factory('')
    }
}
