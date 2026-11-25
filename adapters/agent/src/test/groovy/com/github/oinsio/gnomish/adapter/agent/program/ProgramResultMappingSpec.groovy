package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.ModelUsage
import com.github.oinsio.gnomish.executorcontract.PendingState
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.ToolUsage
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ProgramResultMapping} and {@link ProgramUsage} (FR2, FR6, FR7, FR10, NFR-O1 of
 * define-executor-contract; FR1, FR2, FR5 of migrate-verify-to-executor-contract, design D18, D19):
 * every contract status, failure class and pending state has its one engine counterpart — the
 * identity of the declared pair {@code ExecutionResult} ↔ {@code Status} + {@code FailureClass} +
 * {@code PendingState}, iterated over {@code values()} of both sides — the answer is read by the
 * contract's one reading (by role: {@link ProgramResultAnswerMappingSpec}), a failed or pending
 * result keeps its facts ({@link ProgramResultFactsMappingSpec}), and the usage keeps its provenance
 * and cost.
 */
class ProgramResultMappingSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)

    private static ExecutorResult result(Status status, FailureClass failureClass = null, String reason = null,
            Object answer = null, Usage usage = Usage.absent(), PendingKey key = null) {
        new ExecutorResult(status, failureClass, key, reason == null ? null : UntrustedText.subprocess(reason),
                answer == null ? null : new ObjectMapper().valueToTree(answer), usage, [])
    }

    private static ExecutionResult map(ExecutorResult result, List<Denial> denials = []) {
        ProgramResultMapping.toEngine('acme', Role.TRANSFORM, KEY, result, denials)
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

    // FR19 of migrate-verify-to-executor-contract (design D22): the round closes verifiable exactly
    // when the one mapping maps the result to completed — a decision request included, a verdict
    // round with no answer not; FR10 of extract-agent-round-runner (D11 as amended 2026-11-25): a
    // transform round answering pending goes pending instead of closing, every other result is terminal
    def "FR19: a #role round answering #status goes pending: #pending, closes otherwise #close"() {
        given:
        def contract = switch (status) {
                    case Status.COMPLETED -> result(status, null, null, answer)
                    case Status.FAILED -> result(status, FailureClass.QUALITY, 'red')
                    case Status.PENDING -> result(status, null, null, null, Usage.absent(), new PendingKey('job-9'))
                }

        expect:
        ProgramResultMapping.goesPending(role, contract) == pending
        ProgramResultMapping.closeOf(role, contract) == close

        where:
        role | status | answer || pending | close
        Role.TRANSFORM | Status.COMPLETED | null || false | TerminalClose.VERIFIABLE
        Role.TRANSFORM | Status.COMPLETED | [kind: 'decision-request', question: 'q?', options: ['a', 'b']] || false | TerminalClose.VERIFIABLE
        Role.VERDICT | Status.COMPLETED | null || false | TerminalClose.UNVERIFIABLE
        Role.TRANSFORM | Status.FAILED | null || false | TerminalClose.UNVERIFIABLE
        Role.TRANSFORM | Status.PENDING | null || true | TerminalClose.UNVERIFIABLE
        Role.VERDICT | Status.PENDING | null || false | TerminalClose.UNVERIFIABLE
        Role.DECISION | Status.PENDING | null || false | TerminalClose.UNVERIFIABLE
        Role.TRANSFORM | Status.COMPLETED | [kind: 'decision-request', question: 'q?', options: ['a']] || false | TerminalClose.UNVERIFIABLE
        Role.TRANSFORM | Status.COMPLETED | [kind: 'select', chosen: 'a'] || false | TerminalClose.UNVERIFIABLE
        Role.TRANSFORM | Status.COMPLETED | VERDICT_ANSWER || false | TerminalClose.UNVERIFIABLE
        Role.VERDICT | Status.COMPLETED | VERDICT_ANSWER || false | TerminalClose.VERIFIABLE
        Role.VERDICT | Status.COMPLETED | [kind: 'verdict', sarif: [version: '1.0']] || false | TerminalClose.VERIFIABLE
        Role.VERDICT | Status.COMPLETED | [kind: 'decision-request', question: 'q?', options: ['a', 'b']] || false | TerminalClose.UNVERIFIABLE
        Role.VERDICT | Status.FAILED | null || false | TerminalClose.UNVERIFIABLE
    }

    // FR19 (design D22, human decision 2026-11-12): the close is decided from the contract result
    // before the mapping, and agrees with it for every answer shape of every role — verifiable exactly
    // when the one mapping maps the result to completed
    def "FR19: the close of a completed #role round with #what agrees with its mapping"() {
        given:
        def contract = result(Status.COMPLETED, null, null, answer)

        expect:
        (ProgramResultMapping.closeOf(role, contract) == TerminalClose.VERIFIABLE) ==
                (ProgramResultMapping.toEngine('acme', role, KEY, contract, []).status() instanceof ExecutionResult.Completed)

        where:
        [role, what, answer] << [
            Role.values(),
            ANSWERS.keySet()
        ].combinations().collect { r, w ->
            [r, w, ANSWERS[w]]
        }
    }

    private static final Map VERDICT_ANSWER = [kind: 'verdict', sarif: [version: '2.1.0', runs: [
                [tool: [driver: [name: 'lint']], results: []]
            ]]]

    private static final Map<String, Object> ANSWERS = [
        'no answer': null,
        'a decision request': [kind: 'decision-request', question: 'q?', options: ['a', 'b']],
        'a malformed decision request': [kind: 'decision-request', question: 'q?', options: ['a']],
        'a verdict': VERDICT_ANSWER,
        'a malformed verdict': [kind: 'verdict', sarif: [version: '1.0']],
        'a select': [kind: 'select', chosen: 'a'],
    ]

    // FR2: a pending transform result reaches the engine as pending with its key, which the classifier refuses
    def "FR2: a pending result reaches the engine with its key"() {
        expect:
        (map(result(Status.PENDING, null, null, null, Usage.absent(), new PendingKey('job-9'))).status()
                as ExecutionResult.Pending).key() == new PendingKey('job-9')
    }

    // FR2, D19: every contract pending state maps to the engine state of its name, and the two sets are one
    def "FR2: pending state #contract maps to the engine's #contract.name()"() {
        expect:
        ProgramResultMapping.stateOf(contract).name() == contract.name()
        (PendingState.values()*.name() as Set) ==
                (com.github.oinsio.gnomish.domain.engine.PendingState.values()*.name() as Set)

        where:
        contract << PendingState.values()
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
