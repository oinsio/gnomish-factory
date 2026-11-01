package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * ExecutionResult: what a StageExecutor returns for one round, in the executor contract's shape —
 * a status ({@code Completed}, {@code Failed(class)}, {@code Pending(key)}), an optional answer, the
 * round's usage, trace and denials (FR7 of define-executor-contract, design D4). The former
 * {@code DecisionNeeded} is {@code completed} with a {@code DecisionRequest} answer. Implements
 * FR6, FR13 of add-stage-engine; FR7 of define-executor-contract.
 */
class ExecutionResultSpec extends Specification {

    private static Finding denial() {
        new Finding('egress denied: evil.example.com:443', 'evil.example.com:443', 'kind=connect')
    }

    private static ToolTrace sampleTrace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [
            new ToolCall(0, 'read', Instant.EPOCH, Duration.ZERO)
        ])
    }

    // FR7: the former Completed is status completed with no answer, its telemetry as constructed
    def "completed is status completed with no answer and the telemetry as constructed"() {
        given:
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()

        when:
        def result = ExecutionResult.completed(usage, trace, [denial()])

        then:
        result.status() == new ExecutionResult.Completed()
        result.answer() == null
        result.usage() == usage
        result.trace() == trace
        result.denials() == [denial()]
    }

    // FR7: the former DecisionNeeded is status completed carrying a decision-request answer
    def "decisionNeeded is status completed carrying the decision request"() {
        given:
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()

        when:
        def result = ExecutionResult.decisionNeeded(
                UntrustedText.agent('Which database?'), [
                    UntrustedText.agent('postgres'),
                    UntrustedText.agent('sqlite')
                ], usage, trace, [denial()])

        then:
        result.status() == new ExecutionResult.Completed()
        result.answer() == new ExecutionResult.DecisionRequest(UntrustedText.agent('Which database?'), [
            UntrustedText.agent('postgres'),
            UntrustedText.agent('sqlite')
        ])
        result.usage() == usage
        result.trace() == trace
        result.denials() == [denial()]
    }

    // FR6: the question is required — a blank question asks the human nothing
    def "a decision request rejects a blank question with the component named"() {
        when:
        new ExecutionResult.DecisionRequest(UntrustedText.agent(question), [])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('DecisionRequest.question')

        where:
        question << ['', '   ', '\t', ' \n']
    }

    // FR6: an open-ended question carries no options; the list is copied and unmodifiable
    def "a decision request copies its options and exposes them unmodifiable"() {
        given:
        def source = [UntrustedText.agent('a')]

        when:
        def request = new ExecutionResult.DecisionRequest(UntrustedText.agent('Pick one?'), source)
        source.add(UntrustedText.agent('sneaked in'))

        then:
        request.options()*.forLog() == ['a']
        new ExecutionResult.DecisionRequest(UntrustedText.agent('Open?'), []).options().isEmpty()

        when:
        request.options().add(UntrustedText.agent('b'))

        then:
        thrown(UnsupportedOperationException)
    }

    // FR7: a failure carries its class, its reason and the findings a quality failure feeds forward
    def "a failed status carries its class, reason and copied, unmodifiable findings"() {
        given:
        def finding = new Finding('the build is red', 'Foo.java:3', null)
        def source = [finding]

        when:
        def failed = new ExecutionResult.Failed(
                ExecutionResult.FailureClass.QUALITY, UntrustedText.agent('tests fail'), source)
        source.add(new Finding('sneaked in', null, null))

        then:
        failed.failureClass() == ExecutionResult.FailureClass.QUALITY
        failed.reason() == UntrustedText.agent('tests fail')
        failed.findings() == [finding]

        when:
        failed.findings().add(finding)

        then:
        thrown(UnsupportedOperationException)
    }

    // FR7: a failure that names nothing gives the operator nothing to act on
    def "a failed status rejects a blank reason with the component named"() {
        when:
        new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.agent(reason), [])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Failed.reason')

        where:
        reason << ['', '  ', '\n']
    }

    // FR7: the class vocabulary is the contract's closed set, one constant per class
    def "the failure classes are exactly quality, infrastructure and limit"() {
        expect:
        ExecutionResult.FailureClass.values()*.name() == [
            'QUALITY',
            'INFRASTRUCTURE',
            'LIMIT'
        ]
    }

    // FR7: a pending status carries the executor's key
    def "a pending status carries its key"() {
        expect:
        new ExecutionResult.Pending('k-1').key() == 'k-1'
    }

    // FR3 of fix-denial-report-attachment: the denials are copied — a later mutation of the
    //     guard's list must not rewrite the round's reported denials — and exposed unmodifiable
    def "the denials list is defensively copied and unmodifiable"() {
        given:
        def source = [denial()]

        when:
        def result = new ExecutionResult(new ExecutionResult.Pending('k'), null, ExecutorUsage.none(), sampleTrace(), source)
        source.add(denial())

        then:
        result.denials().size() == 1

        when:
        result.denials().add(denial())

        then:
        thrown(UnsupportedOperationException)
    }

    // FR6: results are values — equal content means equal results
    def "results with the same components are equal values"() {
        expect:
        ExecutionResult.decisionNeeded(UntrustedText.agent('Q?'), [UntrustedText.agent('a')], ExecutorUsage.none(), sampleTrace(), []) ==
        ExecutionResult.decisionNeeded(UntrustedText.agent('Q?'), [UntrustedText.agent('a')], ExecutorUsage.none(), sampleTrace(), [])
        ExecutionResult.decisionNeeded(UntrustedText.agent('Q1?'), [], ExecutorUsage.none(), sampleTrace(), []) !=
        ExecutionResult.decisionNeeded(UntrustedText.agent('Q2?'), [], ExecutorUsage.none(), sampleTrace(), [])
        ExecutionResult.completed(ExecutorUsage.none(), sampleTrace(), []) ==
        ExecutionResult.completed(ExecutorUsage.none(), sampleTrace(), [])
    }
}
