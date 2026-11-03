package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.ModelUsage
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.ToolUsage
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ProgramResultMapping} and {@link ProgramUsage} (FR2, FR6, FR7, FR10, NFR-O1 of
 * define-executor-contract): every contract status and failure class has its one engine
 * counterpart — the identity of the declared pair {@code ExecutionResult} ↔ {@code Status} +
 * {@code FailureClass}, iterated over {@code values()} of both sides — the answer is read by the
 * contract's one reading, and the usage keeps its provenance and cost.
 */
class ProgramResultMappingSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    private static ExecutorResult result(Status status, FailureClass failureClass = null, String reason = null,
            Object answer = null, Usage usage = Usage.absent(), PendingKey key = null) {
        new ExecutorResult(status, failureClass, key, reason == null ? null : UntrustedText.subprocess(reason),
                answer == null ? null : new ObjectMapper().valueToTree(answer), usage, [])
    }

    private static ExecutionResult map(ExecutorResult result, List<Denial> denials = []) {
        ProgramResultMapping.toEngine('acme', KEY, result, denials)
    }

    // FR2, D4: every contract failure class maps to the engine class of its name, and the two sets are one
    def "FR2: failure class #contract maps to the engine's #contract.name()"() {
        expect:
        ProgramResultMapping.classOf(contract).name() == contract.name()
        (FailureClass.values()*.name() as Set) == (ExecutionResult.FailureClass.values()*.name() as Set)

        where:
        contract << FailureClass.values()
    }

    // FR2, D4: every contract status maps to the engine status variant of its name
    def "FR2: status #status maps to the engine status #expected"() {
        given:
        def contract = switch (status) {
                    case Status.COMPLETED -> result(status)
                    case Status.FAILED -> result(status, FailureClass.INFRASTRUCTURE, 'down')
                    case Status.PENDING -> result(status, null, null, null, Usage.absent(), new PendingKey('job-9'))
                }

        expect:
        map(contract).status().class.simpleName == expected

        where:
        status << Status.values()
        expected << [
            'Completed',
            'Failed',
            'Pending'
        ]
    }

    // FR2: a pending transform result reaches the engine as pending with its key, which the classifier refuses
    def "FR2: a pending result reaches the engine with its key"() {
        expect:
        (map(result(Status.PENDING, null, null, null, Usage.absent(), new PendingKey('job-9'))).status()
                as ExecutionResult.Pending).key() == 'job-9'
    }

    // FR7: an executor's quality failure feeds its reason forward as the round's one finding
    def "FR7: failed/quality carries its reason as the one finding; failed/limit carries none"() {
        when:
        def quality = map(result(Status.FAILED, FailureClass.QUALITY, '2 lint errors')).status() as ExecutionResult.Failed
        def limit = map(result(Status.FAILED, FailureClass.LIMIT, 'turns: 20')).status() as ExecutionResult.Failed

        then:
        quality.failureClass() == ExecutionResult.FailureClass.QUALITY
        quality.findings() == [
            new Finding('2 lint errors', null, null)
        ]
        limit.reason().forLog() == 'turns: 20'
        limit.findings() == []
    }

    // FR2: a failed result with no reason is named by the factory, never blank
    def "FR2: a failed result with no reason gets the factory's reason naming the class"() {
        expect:
        (map(result(Status.FAILED, FailureClass.QUALITY)).status() as ExecutionResult.Failed).reason()
                .contains("program executor 'acme' reported failed/quality with no reason")
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

    // FR10, NFR-O1: the usage keeps its provenance, tokens, cost, wall time and tools; ABSENT carries no model
    def "NFR-O1: the usage keeps its provenance, each model's counts and cost, the wall time and the tools"() {
        given:
        def usage = new Usage(provenance, 1500L,
                ['m-1': new ModelUsage(10, 5, 1, 2, 0.02G)], [
                    new ToolUsage('lint', 3, 40L),
                    new ToolUsage('fmt', 1, null)
                ])

        when:
        def mapped = map(result(Status.COMPLETED, null, null, null, usage)).usage()

        then:
        mapped.provenance() == expected
        mapped.wallTime() == Duration.ofMillis(1500)
        mapped.byModel().keySet() == models as Set
        models.isEmpty() || mapped.byModel()['m-1'].costUsd() == 0.02G
        mapped.tools()*.calls() == [3, 1]
        mapped.tools()*.totalDuration() == [
            Duration.ofMillis(40),
            Duration.ZERO
        ]

        where:
        provenance || expected | models
        com.github.oinsio.gnomish.executorcontract.UsageProvenance.REPORTED || UsageProvenance.REPORTED | ['m-1']
        com.github.oinsio.gnomish.executorcontract.UsageProvenance.ABSENT || UsageProvenance.ABSENT | []
    }

    // NFR-O1: a program that reports usage of no model is REPORTED, never ABSENT
    def "NFR-O1: a REPORTED usage of no model stays REPORTED"() {
        expect:
        map(result(Status.COMPLETED, null, null, null,
                new Usage(com.github.oinsio.gnomish.executorcontract.UsageProvenance.REPORTED, null, [:], [])))
                .usage().provenance() == UsageProvenance.REPORTED
    }

    // FR10: the environment's denials come first, the executor's reported ones after, as unidentified findings
    def "FR10: the round's denials are the environment's then the executor's own"() {
        given:
        def environment = Denial.unidentified(new Finding('egress denied: evil.example', null, null))
        def reported = new com.github.oinsio.gnomish.executorcontract.Denial(
                UntrustedText.subprocess('policy refused /etc'), UntrustedText.subprocess('/etc'), null)
        def contract = new ExecutorResult(Status.COMPLETED, null, null, null, null, Usage.absent(), [reported])

        expect:
        map(contract, [environment]).denials() == [
            environment,
            Denial.unidentified(new Finding('policy refused /etc', '/etc', null))
        ]
    }
}
