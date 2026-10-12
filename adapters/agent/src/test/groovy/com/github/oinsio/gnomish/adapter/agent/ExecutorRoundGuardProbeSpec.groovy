package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.sandbox.environment.GuardUnavailableException

/**
 * D14 of tighten-spec-contract (F85), executor side: the round probes its environment's egress
 * guard before the launch and again once the process has exited, before anything is graded or
 * committed. A guard found dead fails the round the way every round that never reached its close
 * fails — an {@code ExecutorFailure} the engine shapes into {@code CannotExecute}, no attempt
 * burned — instead of grading whatever the agent reported. Driven through the real host round
 * with only the environment's guard answer scripted.
 *
 * <p>NFR-R1 of add-sandbox-core.
 */
class ExecutorRoundGuardProbeSpec extends AbstractDenialRoundSpec {

    // D14: the close-of-round probe — the agent exits with a result, but the guard died meanwhile
    // Scenario: sandbox/sandbox-egress / Guard crash burns no attempt
    def "a round whose guard died while it ran fails as infrastructure instead of being graded"() {
        given: 'a guard alive at the round start and dead at its close'
        def source = new ScriptedDenialRounds(hostSource(), [[]])
        source.guardDiesAtProbe = 1

        when: 'the agent completes normally'
        executorFor('plain-round', source).execute(requestFor())

        then: 'the round leaves as an infrastructure failure naming the guard, not as a result'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof GuardUnavailableException
        failure.cause().message.contains('gnomish-guard-k1')

        and: 'the guard was probed at both ends, and the failed round still drained its denials'
        source.probes() == 2
        source.reads() == 1
    }

    // D14: an in-box retry reuses the box — a guard that died after the previous round closed
    //      is caught before the next round starts
    def "an in-box retry whose guard died after the previous round fails before it launches"() {
        given: 'the first round runs with a live guard; the guard dies before the retry'
        def source = new ScriptedDenialRounds(hostSource(), [[]], true)
        source.guardDiesAtProbe = 0

        when: 'the retry would also fail to launch, were it launched'
        executorFor('plain-round', source).execute(requestFor())

        then: 'the probe precedes the launch: the failure is the guard, not the start'
        def failure = thrown(ExecutorFailure)
        failure.cause() instanceof GuardUnavailableException
        source.probes() == 1
    }

    // D14: a live guard costs the round two probes and nothing else
    def "a round whose guard stays alive completes and is probed at both ends"() {
        given:
        def source = new ScriptedDenialRounds(hostSource(), [[]])

        when:
        def result = executorFor('plain-round', source).execute(requestFor())

        then:
        result instanceof ExecutionResult.Completed
        source.probes() == 2
    }
}
