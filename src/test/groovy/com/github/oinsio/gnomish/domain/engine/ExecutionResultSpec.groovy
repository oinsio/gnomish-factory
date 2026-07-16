package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * ExecutionResult: what a StageExecutor returns for one round — either
 * {@code Completed} (proceed to verify) or {@code DecisionNeeded(question, options)}
 * (gnome-initiated escalation, no attempt burned; design D6). Both variants carry
 * the round's {@code ExecutorUsage} and raw {@code ToolTrace}, exposed through the
 * shared interface accessors so the engine reads telemetry without switching
 * (design D5). Implements FR6, FR13 of add-stage-engine.
 */
class ExecutionResultSpec extends Specification {

    private static ToolTrace sampleTrace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [
            new ToolCall(0, 'read', java.time.Instant.EPOCH, java.time.Duration.ZERO)
        ])
    }

    // FR13: Completed exposes the round's usage and trace as constructed
    def "Completed exposes usage and trace as constructed"() {
        given: 'a round usage and its raw trace'
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()

        when: 'a Completed result is created'
        def result = new ExecutionResult.Completed(usage, trace)

        then: 'both telemetry components are exposed exactly as constructed'
        result.usage() == usage
        result.trace() == trace
    }

    // FR13: Completed telemetry is reachable through the ExecutionResult interface accessors
    def "Completed usage and trace are reachable via the ExecutionResult interface"() {
        given: 'a Completed result typed as the sealed interface'
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()
        ExecutionResult result = new ExecutionResult.Completed(usage, trace)

        expect: 'the interface accessors return the same telemetry'
        result.usage() == usage
        result.trace() == trace
    }

    // FR6: DecisionNeeded exposes its question, options, usage and trace as constructed
    def "DecisionNeeded exposes question, options, usage and trace as constructed"() {
        given: 'a question, its options and the round telemetry'
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()

        when: 'a DecisionNeeded result is created'
        def result = new ExecutionResult.DecisionNeeded(
                'Which database?', ['postgres', 'sqlite'], usage, trace)

        then: 'each component is exposed exactly as constructed'
        result.question() == 'Which database?'
        result.options() == ['postgres', 'sqlite']
        result.usage() == usage
        result.trace() == trace
    }

    // FR13: DecisionNeeded telemetry is reachable through the interface accessors
    def "DecisionNeeded usage and trace are reachable via the ExecutionResult interface"() {
        given: 'a DecisionNeeded result typed as the sealed interface'
        def usage = ExecutorUsage.none()
        def trace = sampleTrace()
        ExecutionResult result = new ExecutionResult.DecisionNeeded(
                'Proceed?', [], usage, trace)

        expect: 'the interface accessors return the same telemetry'
        result.usage() == usage
        result.trace() == trace
    }

    // FR6: the question is required — a blank question asks the human nothing
    def "DecisionNeeded rejects a blank question with the component named"() {
        when: 'a DecisionNeeded is created with a blank question'
        new ExecutionResult.DecisionNeeded(question, [], ExecutorUsage.none(), sampleTrace())

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('DecisionNeeded.question')

        where:
        question << ['', '   ', '\t', ' \n']
    }

    // FR6: an open-ended question carries no options — an empty options list is valid
    def "DecisionNeeded accepts an empty options list"() {
        when: 'a DecisionNeeded is created with no options'
        def result = new ExecutionResult.DecisionNeeded(
                'Open question?', [], ExecutorUsage.none(), sampleTrace())

        then: 'the options list is empty'
        result.options().isEmpty()
    }

    // FR6: options are copied on construction — later source mutation cannot leak in
    def "the options list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = ['a']

        when: 'a DecisionNeeded is created and the source is then mutated'
        def result = new ExecutionResult.DecisionNeeded(
                'Pick one?', source, ExecutorUsage.none(), sampleTrace())
        source.add('sneaked in')

        then: 'the result keeps its original single option'
        result.options() == ['a']
    }

    // FR6: the exposed options list is unmodifiable — the engine carries it verbatim
    def "the exposed options list is unmodifiable"() {
        given: 'a DecisionNeeded result'
        def result = new ExecutionResult.DecisionNeeded(
                'Pick one?', ['a'], ExecutorUsage.none(), sampleTrace())

        when: 'a caller tries to add an option'
        result.options().add('b')

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR6: ExecutionResult is sealed — an exhaustive switch handles both variants
    def "an exhaustive switch over ExecutionResult handles both variants"() {
        expect: 'each variant is matched to its own arm'
        describe(result) == expected

        where:
        result                                                                             | expected
        new ExecutionResult.Completed(ExecutorUsage.none(), sampleTrace())                 | 'completed'
        new ExecutionResult.DecisionNeeded('Q?', [], ExecutorUsage.none(), sampleTrace())  | 'decision: Q?'
    }

    // FR6: Completed results are values — equal content means equal results
    def "Completed results with the same components are equal values"() {
        expect: 'two independently constructed Completed results with equal content are equal'
        new ExecutionResult.Completed(ExecutorUsage.none(), sampleTrace()) ==
                new ExecutionResult.Completed(ExecutorUsage.none(), sampleTrace())
    }

    // FR6: DecisionNeeded results are values — equal content means equal results
    def "DecisionNeeded results with the same components are equal values"() {
        expect: 'two independently constructed DecisionNeeded results with equal content are equal'
        new ExecutionResult.DecisionNeeded('Q?', ['a'], ExecutorUsage.none(), sampleTrace()) ==
        new ExecutionResult.DecisionNeeded('Q?', ['a'], ExecutorUsage.none(), sampleTrace())

        and: 'a differing question makes them unequal'
        new ExecutionResult.DecisionNeeded('Q1?', [], ExecutorUsage.none(), sampleTrace()) !=
        new ExecutionResult.DecisionNeeded('Q2?', [], ExecutorUsage.none(), sampleTrace())
    }

    private static String describe(ExecutionResult result) {
        switch (result) {
            case ExecutionResult.Completed: return 'completed'
            case ExecutionResult.DecisionNeeded:
                return 'decision: ' + ((ExecutionResult.DecisionNeeded) result).question()
            default: throw new IllegalStateException('unreachable')
        }
    }
}
