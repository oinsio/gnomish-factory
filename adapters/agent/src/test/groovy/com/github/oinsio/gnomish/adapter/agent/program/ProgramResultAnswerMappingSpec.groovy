package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link ProgramResultMapping}, the answer a round takes by its role (FR3, FR6 of
 * define-executor-contract; FR1, FR5, FR6 of migrate-verify-to-executor-contract, design D18): a
 * transform round takes a decision request, a verdict round a verdict — which crosses the port as a
 * {@code VerdictReply}, each SARIF result read by the one reading, its details the finding's — and
 * every other answer, a malformed transform answer and a verdict round with none are each
 * {@code failed/infrastructure}, never a pass.
 */
class ProgramResultAnswerMappingSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    private static ExecutorResult result(Status status, FailureClass failureClass = null, String reason = null,
            Object answer = null, Usage usage = Usage.absent(), PendingKey key = null) {
        new ExecutorResult(status, failureClass, key, reason == null ? null : UntrustedText.subprocess(reason),
                answer == null ? null : new ObjectMapper().valueToTree(answer), usage, [])
    }

    private static ExecutionResult map(ExecutorResult result, List<Denial> denials = []) {
        ProgramResultMapping.toEngine('acme', Role.TRANSFORM, KEY, result, denials)
    }

    private static ExecutionResult verdict(ExecutorResult result) {
        ProgramResultMapping.toEngine('acme', Role.VERDICT, KEY, result, [])
    }

    private static Map sarif(List<Map> results) {
        [kind: 'verdict', sarif: [version: '2.1.0', runs: [
                    [tool: [driver: [name: 'lint']], results: results]
                ]]]
    }

    // FR1, FR5, D18: a verdict round's SARIF answer crosses the port as a verdict reply, each result read once
    def "FR5: a verdict round's SARIF error result with properties.details becomes a finding with those details, its message unchanged"() {
        when:
        def mapped = verdict(result(Status.COMPLETED, null, null, sarif([
            [level: 'error', message: [text: 'build red'], properties: [details: 'last 40 lines'],
                locations: [
                    [physicalLocation: [artifactLocation: [uri: 'src/A.java'], region: [startLine: 3]]]
                ]],
            [level: 'note', message: [text: 'fyi']]
        ])))

        then:
        mapped.status() instanceof ExecutionResult.Completed
        (mapped.answer() as ExecutionResult.VerdictReply).answer() == new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('build red', 'src/A.java:3', 'last 40 lines')),
            new VerdictAnswer.Result(ResultLevel.NOTE, new Finding('fyi', null, null))
        ])
    }

    // FR6, D18: a malformed verdict answer crosses as a malformed reply, which the derivation resolves as cannot-verify
    def "FR6: a malformed verdict answer of a verdict round is a malformed reply, never a pass"() {
        when:
        def mapped = verdict(result(Status.COMPLETED, null, null, [kind: 'verdict', sarif: [version: '1.0']]))

        then:
        def reply = (mapped.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Malformed
        reply.reason().contains("only 2.1.0 is read")
    }

    // FR6, D18: a verdict round takes a verdict only; another kind, or no answer, is failed/infrastructure
    def "FR6: a verdict round that completed with #what is failed/infrastructure naming it"() {
        when:
        def mapped = verdict(result(Status.COMPLETED, null, null, answer))

        then:
        mapped.answer() == null
        def failed = mapped.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().contains("program executor 'acme' completed with " + says)
        failed.details() == null

        where:
        what | answer || says
        'a decision request' | [kind: 'decision-request', question: 'Which?', options: ['a', 'b']] || 'an answer of a kind a verdict run does not take'
        'no answer' | null || 'no answer'
    }

    // D18: a stage round never takes a verdict reply; its verdict answer is refused as a kind it does not take
    def "D18: a transform round refuses a verdict answer"() {
        when:
        def mapped = map(result(Status.COMPLETED, null, null, sarif([
            [level: 'error', message: [text: 'x']]
        ])))

        then:
        mapped.answer() == null
        (mapped.status() as ExecutionResult.Failed).reason().contains('an answer of a kind a transform run does not take')
    }

    // Scenario: executor/wire-contract / Decision request with one option is malformed
    // FR3, FR6: a decision request is a decision; a malformed answer or another kind is failed/infrastructure, never a pass
    def "FR3, FR6: a #what answer of a transform run maps to #expected"() {
        when:
        def mapped = map(result(Status.COMPLETED, null, null, answer))

        then:
        (mapped.answer() != null) == (expected == 'decision')
        (mapped.status() instanceof ExecutionResult.Failed) == (expected == 'failed/infrastructure')
        expected != 'failed/infrastructure' || (mapped.status() as ExecutionResult.Failed).reason().contains(says)

        where:
        what | answer || expected | says
        'decision-request' | [kind: 'decision-request', question: 'Which?', options: ['a', 'b']] || 'decision' | ''
        'one-option decision' | [kind: 'decision-request', question: 'Which?', options: ['a']] || 'failed/infrastructure' | 'a malformed answer: decision-request.options holds 1 items; at least 2 are required, 1 missing'
        'select' | [kind: 'select', chosen: 'a'] || 'failed/infrastructure' | 'a transform run does not take'
    }
}
