package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * ExecutionResult's check-round shapes (FR1, FR2 of migrate-verify-to-executor-contract, design
 * D18, D19): a failed status carries its details apart from its reason, a pending status its wait
 * hint, its {@link PendingState} and its texts, and a check round's answer is a {@code
 * VerdictReply}.
 */
class ExecutionResultCheckRoundSpec extends Specification {

    private static ToolTrace sampleTrace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    }

    // D18 as amended (D19 review) of migrate-verify-to-executor-contract: a failure carries its details apart from its reason
    def "a failed status carries its details beside the reason, or none"() {
        expect:
        new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.agent('down'), [],
        UntrustedText.subprocess('stack')).details() == UntrustedText.subprocess('stack')
        new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, UntrustedText.agent('red'), [], null)
        .details() == null
    }

    // D19: a pending status carries its typed facts and its texts
    def "a pending status carries its key, wait hint, state and texts"() {
        when:
        def pending = new ExecutionResult.Pending(new PendingKey('k-1'), Duration.ofSeconds(30), PendingState.UNOBSERVED,
                UntrustedText.subprocess('rate limited'), UntrustedText.subprocess('reset in 30 s'))

        then:
        pending.key() == new PendingKey('k-1')
        pending.pollAfter() == Duration.ofSeconds(30)
        pending.state() == PendingState.UNOBSERVED
        pending.reason() == UntrustedText.subprocess('rate limited')
        pending.details() == UntrustedText.subprocess('reset in 30 s')
    }

    // D19: the engine's pending states are the contract's closed set
    def "the pending states are exactly running, unreported and unobserved"() {
        expect:
        PendingState.values()*.name() == [
            'RUNNING',
            'UNREPORTED',
            'UNOBSERVED'
        ]
    }

    // FR1, D18: a check round's answer is a verdict reply, carried as the answer of a completed result
    def "a verdict reply is an answer carrying the verdict answer"() {
        given:
        def answer = new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('red', null, 'tail'))
        ], null)

        when:
        def result = new ExecutionResult(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(answer),
                ExecutorUsage.none(), sampleTrace(), [])

        then:
        result.answer() instanceof ExecutionResult.Answer
        (result.answer() as ExecutionResult.VerdictReply).answer() == answer
    }
}
