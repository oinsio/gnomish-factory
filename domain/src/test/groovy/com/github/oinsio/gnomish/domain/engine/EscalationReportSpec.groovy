package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * EscalationReport: the data-only reason a task was escalated — one of five kinds:
 * {@code AttemptsExhausted}, {@code DecisionNeeded}, {@code CannotVerify},
 * {@code PipelineMismatch}, {@code CannotExecute}. Each carries only the data not
 * already in the final {@code TaskState} (current stage and attempt history live
 * there), so a report is renderable from the outcome and its final state alone
 * (UX1, design D1). Implements FR10 of add-stage-engine.
 *
 * <p>This spec covers per-variant construction and validation / defensive-copy
 * rules; the sealed-hierarchy behaviour lives in {@code EscalationReportSealedSpec}.
 */
class EscalationReportSpec extends Specification {

    private static CheckRef sampleCheck() {
        new CheckRef(0, 'command:./gradlew build')
    }

    // FR10: AttemptsExhausted exposes the resolved attempt limit as constructed
    def "AttemptsExhausted exposes its limit as constructed"() {
        when: 'an AttemptsExhausted report is created'
        def report = new EscalationReport.AttemptsExhausted(3)

        then: 'the limit is exposed exactly as constructed'
        report.limit() == 3
    }

    // FR10: a validated limit round-trips a specific non-trivial literal (≠1, ≠0)
    // (pins requireAtLeastOne's return against a return-value mutation)
    def "AttemptsExhausted limit round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-trivial limit it was built with'
        new EscalationReport.AttemptsExhausted(5).limit() == 5
    }

    // FR10: DecisionNeeded exposes the question and free-text options as constructed
    def "DecisionNeeded exposes question and options as constructed"() {
        when: 'a DecisionNeeded report is created'
        def report = new EscalationReport.DecisionNeeded('Which database?', ['postgres', 'sqlite'])

        then: 'each component is exposed exactly as constructed'
        report.question() == 'Which database?'
        report.options() == ['postgres', 'sqlite']
    }

    // FR10: CannotVerify exposes the check, reason and details as constructed
    def "CannotVerify exposes check, reason and details as constructed"() {
        given: 'the check that could not be verified'
        def check = sampleCheck()

        when: 'a CannotVerify report is created'
        def report = new EscalationReport.CannotVerify(check, 'CI unavailable', 'HTTP 503 ...')

        then: 'each component is exposed exactly as constructed'
        report.check() == check
        report.reason() == 'CI unavailable'
        report.details() == 'HTTP 503 ...'
    }

    // FR10: PipelineMismatch exposes the stale stage name as constructed
    def "PipelineMismatch exposes its staleStage as constructed"() {
        when: 'a PipelineMismatch report is created'
        def report = new EscalationReport.PipelineMismatch('legacy-build')

        then: 'the stale stage name is exposed exactly as constructed'
        report.staleStage() == 'legacy-build'
    }

    // FR10: CannotExecute exposes the preserved cause as constructed
    def "CannotExecute exposes its cause as constructed"() {
        when: 'a CannotExecute report is created'
        def report = new EscalationReport.CannotExecute('network error: java.net.ConnectException ...')

        then: 'the cause is exposed exactly as constructed'
        report.cause() == 'network error: java.net.ConnectException ...'
    }

    // FR10: an attempt limit below one makes no sense — zero and negative are rejected
    def "AttemptsExhausted rejects a limit below one with the component named"() {
        when: 'an AttemptsExhausted is created with a limit below one'
        new EscalationReport.AttemptsExhausted(limit)

        then: 'construction fails and the message names the component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptsExhausted.limit')

        where:
        limit << [0, -1, -7]
    }

    // FR10: a decision that asks nothing cannot be answered — a blank question is rejected
    def "DecisionNeeded rejects a blank question with the component named"() {
        when: 'a DecisionNeeded is created with a blank question'
        new EscalationReport.DecisionNeeded(question, [])

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('DecisionNeeded.question')

        where:
        question << ['', '   ', '\t', ' \n']
    }

    // FR10: an open-ended decision carries no options — an empty options list is valid
    def "DecisionNeeded accepts an empty options list"() {
        when: 'a DecisionNeeded is created with no options'
        def report = new EscalationReport.DecisionNeeded('Open question?', [])

        then: 'the options list is empty'
        report.options().isEmpty()
    }

    // FR10: options are copied on construction — later source mutation cannot leak in
    def "DecisionNeeded options are defensively copied from the source"() {
        given: 'a mutable source list'
        def source = ['a']

        when: 'a DecisionNeeded is created and the source is then mutated'
        def report = new EscalationReport.DecisionNeeded('Pick one?', source)
        source.add('sneaked in')

        then: 'the report keeps its original single option'
        report.options() == ['a']
    }

    // FR10: the exposed options list is unmodifiable — it is carried verbatim
    def "DecisionNeeded options are unmodifiable"() {
        given: 'a DecisionNeeded report'
        def report = new EscalationReport.DecisionNeeded('Pick one?', ['a'])

        when: 'a caller tries to add an option'
        report.options().add('b')

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR10: CannotVerify.reason is required — a report must state why verification failed
    def "CannotVerify rejects a blank reason with the component named"() {
        when: 'a CannotVerify is created with a blank reason'
        new EscalationReport.CannotVerify(sampleCheck(), reason, 'details')

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CannotVerify.reason')

        where:
        reason << ['', '   ', '\t', ' \n']
    }

    // FR10: CannotVerify.details may be empty — an adapter need not always have a stack trace
    def "CannotVerify accepts empty details"() {
        when: 'a CannotVerify is created with empty details'
        def report = new EscalationReport.CannotVerify(sampleCheck(), 'CI unavailable', '')

        then: 'the details are the empty string'
        report.details() == ''
    }

    // FR10: PipelineMismatch.staleStage is required — the report names the missing stage
    def "PipelineMismatch rejects a blank staleStage with the component named"() {
        when: 'a PipelineMismatch is created with a blank staleStage'
        new EscalationReport.PipelineMismatch(staleStage)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('PipelineMismatch.staleStage')

        where:
        staleStage << ['', '   ', '\t', ' \n']
    }

    // FR10: CannotExecute.cause is required — the preserved stack trace must be present
    def "CannotExecute rejects a blank cause with the component named"() {
        when: 'a CannotExecute is created with a blank cause'
        new EscalationReport.CannotExecute(cause)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CannotExecute.cause')

        where:
        cause << ['', '   ', '\t', ' \n']
    }
}
