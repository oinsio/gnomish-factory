package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * Stop: the escalation content a recorded round carries (design D3). Implements FR5 of
 * make-checkpoint-gate-durable.
 */
class StopSpec extends Specification {

    private static final CheckRef CHECK = new CheckRef(0, UntrustedText.manifest('builtin:files_exist'))

    // FR5: the stop vocabulary is closed — exactly None, DecisionNeeded and CannotVerify
    def "permits exactly the three stop kinds"() {
        expect:
        Stop.permittedSubclasses*.simpleName as Set == [
            'None',
            'DecisionNeeded',
            'CannotVerify'
        ] as Set
    }

    // FR5: none() is the stop of a round that raised none
    def "none() yields the None stop"() {
        expect:
        Stop.none() instanceof Stop.None
        Stop.none() == new Stop.None()
    }

    // FR5: a DecisionNeeded stop carries the question and options verbatim
    def "DecisionNeeded carries the question and its options"() {
        when:
        def stop = new Stop.DecisionNeeded(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')])

        then:
        stop.question() == UntrustedText.agent('which db?')
        stop.options() == [UntrustedText.agent('pg')]
    }

    // FR5: a DecisionNeeded stop that asks nothing is refused
    def "DecisionNeeded refuses a blank question"() {
        when:
        new Stop.DecisionNeeded(UntrustedText.agent('  '), [])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'Stop.DecisionNeeded.question must not be blank'
    }

    // FR5: options are defensively copied and unmodifiable
    def "DecisionNeeded copies its options"() {
        given:
        def source = [UntrustedText.agent('pg')]

        when:
        def stop = new Stop.DecisionNeeded(UntrustedText.agent('which db?'), source)
        source.add(UntrustedText.agent('mysql'))

        then:
        stop.options().size() == 1

        when:
        stop.options().add(UntrustedText.agent('sqlite'))

        then:
        thrown(UnsupportedOperationException)
    }

    // FR5: a CannotVerify stop carries the check, reason and details
    def "CannotVerify carries the check, the reason and the details"() {
        when:
        def stop = new Stop.CannotVerify(CHECK, UntrustedText.subprocess('tool missing'), UntrustedText.subprocess(''))

        then:
        stop.check() == CHECK
        stop.reason() == UntrustedText.subprocess('tool missing')
        stop.details() == UntrustedText.subprocess('')
    }

    // FR5: a CannotVerify stop that cannot name why is refused
    def "CannotVerify refuses a blank reason"() {
        when:
        new Stop.CannotVerify(CHECK, UntrustedText.subprocess(''), UntrustedText.subprocess('details'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'Stop.CannotVerify.reason must not be blank'
    }
}
