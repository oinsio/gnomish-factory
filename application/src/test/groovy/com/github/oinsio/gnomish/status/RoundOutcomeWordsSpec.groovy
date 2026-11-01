package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.RoundExecutor
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * NFR-O2, UX3 of define-executor-contract: the round's words are the executor contract's own wire
 * tokens — every engine failure class reads back through the contract's parse to its namesake, so
 * the declared pair {@code ExecutionResult} ↔ contract {@code Status}/{@code FailureClass} cannot
 * drift on the operator's planes.
 */
class RoundOutcomeWordsSpec extends Specification {

    static final UntrustedText REASON = UntrustedText.subprocess('turns 40')

    // NFR-R3, UX3: every engine failure class maps to the contract token of the same name
    def "failure class #engine reads back to the contract's #engine"() {
        when:
        def token = RoundOutcomeWords.failureClass(new ExecutionResult.Failed(engine, REASON, []))

        then:
        FailureClass.fromWire(token).get().name() == engine.name()

        where:
        engine << ExecutionResult.FailureClass.values()
    }

    // UX3: status and status-with-class tokens
    def "status #status is spelled #word and #withClass"() {
        expect:
        Status.fromWire(RoundOutcomeWords.status(status)).isPresent()
        RoundOutcomeWords.status(status) == word
        RoundOutcomeWords.statusWithClass(status) == withClass

        where:
        status | word | withClass
        new ExecutionResult.Completed() | 'completed' | 'completed'
        new ExecutionResult.Pending('k-1') | 'pending' | 'pending'
        new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, REASON, []) | 'failed' | 'failed/quality'
        new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, REASON, []) | 'failed' | 'failed/infrastructure'
        new ExecutionResult.Failed(ExecutionResult.FailureClass.LIMIT, REASON, []) | 'failed' | 'failed/limit'
    }

    // NFR-O2: only a limit failure reached a bound; a non-failed status has no class
    def "the bound is the reason of a limit failure only"() {
        expect:
        RoundOutcomeWords.bound(new ExecutionResult.Failed(ExecutionResult.FailureClass.LIMIT, REASON, [])) == REASON
        RoundOutcomeWords.bound(new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, REASON, [])) == null
        RoundOutcomeWords.bound(new ExecutionResult.Completed()) == null
        RoundOutcomeWords.failureClass(new ExecutionResult.Completed()) == null
        RoundOutcomeWords.failureClass(new ExecutionResult.Pending('k')) == null
    }

    // NFR-O2: a described executor speaks the factory's one protocol version; an undescribed one none
    def "the protocol version is named for a described executor only"() {
        expect:
        RoundOutcomeWords.protocolVersion(new RoundExecutor('lint-bot', UntrustedText.subprocess('2'))) ==
                Describe.SPOKEN_PROTOCOL_VERSION
        Describe.SPOKEN_PROTOCOL_VERSION == 1
        RoundOutcomeWords.protocolVersion(new RoundExecutor('agent-cli', null)) == null
    }

    // UX3: the log plane's executor reference, and the stage role
    def "the executor is name@version, or the name alone, and a stage round is a transform"() {
        expect:
        RoundOutcomeWords.executorForLog(new RoundExecutor('lint-bot', UntrustedText.subprocess('2.3.1'))) == 'lint-bot@2.3.1'
        RoundOutcomeWords.executorForLog(new RoundExecutor('agent-cli', null)) == 'agent-cli'
        RoundOutcomeWords.STAGE_ROLE == 'transform'
    }
}
