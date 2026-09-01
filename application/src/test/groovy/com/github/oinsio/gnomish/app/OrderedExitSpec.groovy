package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * {@link OrderedExit}: the single teardown order every command exits through — context close, then
 * logging stop — and the two rules that keep it single: a normal return steps aside once the
 * shutdown hook has taken ownership, and a second pass changes nothing.
 *
 * <p>FR9, NFR-R1 of harden-logging-observability.
 */
class OrderedExitSpec extends Specification {

    List<String> steps = []

    def setup() {
        ShutdownPhase.reset()
        OrderedExit.install({
            steps << 'closeContext'
        }, {
            steps << 'stopLogging'
        })
    }

    def cleanup() {
        ShutdownPhase.reset()
        OrderedExit.install({}, {})
    }

    def "FR9: the context closes before logging stops"() {
        when:
        OrderedExit.closeAndStopLogging()

        then: 'the async file appender is still accepting lines while the context tears down'
        steps == ['closeContext', 'stopLogging']
    }

    def "FR9: a normal exit runs the same sequence when no stop has begun"() {
        when:
        OrderedExit.onNormalExit()

        then:
        steps == ['closeContext', 'stopLogging']
    }

    // The race this class exists to remove: on a signal-initiated stop the hook is still draining
    // slots when `main` returns from its runner, and a context closed out from under that drain
    // takes the terminal lines with it.
    def "FR9: a normal exit defers entirely once the shutdown phase has begun"() {
        given:
        ShutdownPhase.begin()

        when:
        OrderedExit.onNormalExit()

        then:
        steps.isEmpty()
    }

    def "FR9: the generic signal hook runs the sequence when no command owns the stop"() {
        when:
        OrderedExit.onSignal()

        then:
        steps == ['closeContext', 'stopLogging']
    }

    // Reserved at hook-REGISTRATION time, not at fire time, so the two JVM hooks never race to
    // discover each other.
    def "FR9: the generic signal hook stands down once a command reserves the stop"() {
        given:
        OrderedExit.reserveSignalOwner()

        when:
        OrderedExit.onSignal()

        then:
        steps.isEmpty()
    }

    def "a fresh install clears a previous run's reservation"() {
        given:
        OrderedExit.reserveSignalOwner()

        when:
        OrderedExit.install({
            steps << 'closeContext'
        }, {
            steps << 'stopLogging'
        })
        OrderedExit.onSignal()

        then:
        steps == ['closeContext', 'stopLogging']
    }

    def "NFR-R1: a second pass is a no-op"() {
        given:
        OrderedExit.closeAndStopLogging()

        when:
        OrderedExit.closeAndStopLogging()

        then:
        steps == ['closeContext', 'stopLogging']
    }

    def "NFR-R1: the hook's pass after a completed normal exit is a no-op"() {
        given:
        OrderedExit.onNormalExit()

        when:
        OrderedExit.closeAndStopLogging()

        then:
        steps == ['closeContext', 'stopLogging']
    }

    // A context that fails to close must not cost the run its log file: the flush is the last
    // thing standing between a crash-on-shutdown and an operator with no evidence of it.
    // A spec drives the serve shutdown hook with no composition root behind it, and a run that
    // dies before `CommandExit.start` never installs one either. Neither may throw.
    def "both entry points are no-ops before anything is installed"() {
        given:
        def field = OrderedExit.getDeclaredField('installed')
        field.setAccessible(true)
        field.set(null, null)

        when:
        OrderedExit.closeAndStopLogging()
        OrderedExit.onNormalExit()

        then:
        noExceptionThrown()
        steps.isEmpty()
    }

    def "FR9: logging still stops when the context close fails"() {
        given:
        OrderedExit.install({
            throw new IllegalStateException('context refused to close')
        }, {
            steps << 'stopLogging'
        })

        when:
        OrderedExit.closeAndStopLogging()

        then:
        steps == ['stopLogging']
    }
}
