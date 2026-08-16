package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * CheckResult: the outcome of running one verify check — which check
 * ({@code checkRef}), its {@code verdict}, and the wall {@code duration} it took.
 * Implements FR4 of add-stage-engine.
 */
class CheckResultSpec extends Specification {

    // FR4: CheckResult exposes checkRef, verdict and duration as constructed
    def "exposes checkRef, verdict and duration as constructed"() {
        given: 'a check reference and a verdict'
        def ref = new CheckRef(0, 'builtin:files_exist')
        def verdict = new Verdict.Pass()
        def duration = Duration.ofMillis(250)

        when: 'a CheckResult is created'
        def result = new CheckResult(ref, verdict, duration)

        then: 'all three components are exposed exactly as constructed'
        result.checkRef() == ref
        result.verdict() == verdict
        result.duration() == duration
    }

    // FR4: a result works with each Verdict variant
    def "carries any Verdict variant"() {
        given: 'a check reference'
        def ref = new CheckRef(1, 'command:make')

        when: 'a CheckResult is created with the verdict'
        def result = new CheckResult(ref, verdict, Duration.ofSeconds(1))

        then: 'the verdict is carried through'
        result.verdict() == verdict

        where:
        verdict << [
            new Verdict.Pass(),
            new Verdict.Fail([
                new Finding('boom', null, null)
            ]),
            new Verdict.CannotVerify('binary not found', ''),
        ]
    }

    // FR4: a zero duration is valid — a check may take no measurable time
    def "accepts a zero duration"() {
        when: 'a CheckResult is created with a zero duration'
        def result = new CheckResult(new CheckRef(0, 'builtin:x'), new Verdict.Pass(), Duration.ZERO)

        then: 'the zero duration is exposed as constructed'
        result.duration() == Duration.ZERO
    }

    // FR4: a duration cannot be negative — a negative duration is rejected
    def "rejects a negative duration with the component named"() {
        when: 'a CheckResult is created with a negative duration'
        new CheckResult(new CheckRef(0, 'builtin:x'), new Verdict.Pass(), negative)

        then: 'construction fails and the message names the duration'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CheckResult.duration')

        where:
        negative << [
            Duration.ofMillis(-1),
            Duration.ofSeconds(-10)
        ]
    }

    // FR4: CheckResult is inert value data compared by content
    def "is value-equal by content"() {
        given: 'two results built from equal components'
        def ref = new CheckRef(0, 'builtin:x')

        expect: 'they are equal'
        new CheckResult(ref, new Verdict.Pass(), Duration.ofMillis(5)) ==
                new CheckResult(ref, new Verdict.Pass(), Duration.ofMillis(5))

        and: 'a differing duration makes them unequal'
        new CheckResult(ref, new Verdict.Pass(), Duration.ofMillis(5)) !=
                new CheckResult(ref, new Verdict.Pass(), Duration.ofMillis(6))
    }
}
