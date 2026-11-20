package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR1, FR2, FR5, FR7 of migrate-verify-to-executor-contract (design D18): the verdict answer a
 * check round's terminal result stands for — the verdict reply as it is, a completed round with no
 * reply a malformed answer, a quality failure with its findings, an infrastructure or limit failure
 * as an infrastructure failure with its reason and details.
 */
class CheckRoundAnswersSpec extends Specification {

    static final def TRACE = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    static final def RED = new Finding('red', null, 'tail')

    static ExecutionResult result(ExecutionResult.Status status, ExecutionResult.Answer answer = null) {
        new ExecutionResult(status, answer, ExecutorUsage.none(), TRACE, [])
    }

    static ExecutionResult failed(ExecutionResult.FailureClass failureClass, UntrustedText details) {
        result(new ExecutionResult.Failed(failureClass, UntrustedText.subprocess('why'), [RED], details))
    }

    def "FR7: #why"() {
        expect:
        CheckRoundAnswers.of(result) == answer

        where:
        why | result || answer
        'a verdict reply passes through' | result(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(
                        new VerdictAnswer.Sarif([], 'https://ci/1'))) || new VerdictAnswer.Sarif([], 'https://ci/1')
        'a completed round with no reply is malformed' | result(new ExecutionResult.Completed()) || new VerdictAnswer.Malformed(
                UntrustedText.factory('the check round completed with no verdict answer'))
        'a decision request is no verdict' | result(new ExecutionResult.Completed(), new ExecutionResult.DecisionRequest(
                        UntrustedText.agent('q'), [])) || new VerdictAnswer.Malformed(UntrustedText.factory('the check round completed with no verdict answer'))
        'a quality failure keeps its findings' | failed(ExecutionResult.FailureClass.QUALITY, null) || new VerdictAnswer.QualityFailure([RED])
        'an infrastructure failure keeps its reason and details' | failed(ExecutionResult.FailureClass.INFRASTRUCTURE,
                UntrustedText.subprocess('d')) || new VerdictAnswer.InfrastructureFailure(UntrustedText.subprocess('why'), UntrustedText.subprocess('d'))
        'a limit obtained no verdict' | failed(ExecutionResult.FailureClass.LIMIT, null) || new VerdictAnswer.InfrastructureFailure(
                UntrustedText.subprocess('why'), UntrustedText.factory(''))
    }

    def "a pending round has not ended, so it has no answer"() {
        when:
        CheckRoundAnswers.of(result(new ExecutionResult.Pending(new PendingKey('k'), null, PendingState.RUNNING, null, null)))

        then:
        thrown(IllegalArgumentException)
    }
}
