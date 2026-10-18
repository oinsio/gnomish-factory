package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.logtext.ShutdownPhase
import spock.lang.Specification

/**
 * {@link OrderedExit}'s shutdown step (FR21, design D19 of fix-terminal-receipt-convergence, fourth
 * amendment): a signal reaching a command that owns no hook runs the step it was installed with —
 * the process tree killed and every live group swept — before the context closes and logging stops;
 * a command that owns its stop, and an ordinary return, never run it here. The step itself is
 * driven on real processes by {@code ShutdownSweepSpec}.
 */
class OrderedExitShutdownStepSpec extends Specification {

    List<String> steps = Collections.synchronizedList(new ArrayList<String>())

    def setup() {
        ShutdownPhase.reset()
        OrderedExit.install({
            steps << 'closeContext'
        }, {
            steps << 'stopLogging'
        }, {
            steps << 'shutdownStep'
        })
    }

    def cleanup() {
        ShutdownPhase.reset()
        OrderedExit.install({}, {}, {})
    }

    def "FR21: a signal runs the shutdown step before the context closes and logging stops"() {
        when:
        OrderedExit.onSignal()

        then:
        steps == [
            'shutdownStep',
            'closeContext',
            'stopLogging'
        ]
    }

    def "FR21: a shutdown step that throws still leaves the context closed and the log flushed"() {
        given:
        OrderedExit.install({
            steps << 'closeContext'
        }, {
            steps << 'stopLogging'
        }, {
            throw new IllegalStateException('kill failed')
        })

        when:
        OrderedExit.onSignal()

        then:
        thrown(IllegalStateException)
        steps == ['closeContext', 'stopLogging']
    }

    def "FR21: a command that owns its stop runs the step in its own hook, not here"() {
        given:
        OrderedExit.reserveSignalOwner()

        when:
        OrderedExit.onSignal()

        then:
        steps.isEmpty()
    }

    def "FR21: an ordinary return runs no shutdown step: every waiter has swept its own group"() {
        when:
        OrderedExit.onNormalExit()
        OrderedExit.closeAndStopLogging()

        then:
        steps == ['closeContext', 'stopLogging']
    }
}
