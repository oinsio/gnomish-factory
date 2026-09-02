package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.logtext.ShutdownPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    // FR9: an Error is not a RuntimeException, so a catch of the latter alone would skip the
    // flush on exactly the failures whose queued lines the operator needs — an OutOfMemoryError or
    // a NoClassDefFoundError raised while a bean closes.
    def "FR9: logging still stops when the context close fails with an Error"() {
        given:
        OrderedExit.install({
            throw new NoClassDefFoundError('the closing bean class went missing')
        }, {
            steps << 'stopLogging'
        })

        when:
        OrderedExit.closeAndStopLogging()

        then: 'the Error still reaches the caller, but the appender was flushed first'
        thrown(NoClassDefFoundError)
        steps == ['stopLogging']
    }

    // NFR-R1: the loser of the guard is the generic signal hook racing a `main` that entered the
    // sequence a moment earlier. The JVM halts once its hooks return, so a hook that returns while
    // the flush is still running takes the tail of the log file with it.
    def "NFR-R1: the caller that loses the guard waits for the sequence to finish"() {
        given: 'a context close that blocks until the test releases it'
        def closeEntered = new CountDownLatch(1)
        def releaseClose = new CountDownLatch(1)
        OrderedExit.install({
            steps << 'closeContext'
            closeEntered.countDown()
            releaseClose.await(5, TimeUnit.SECONDS)
        }, {
            steps << 'stopLogging'
        })

        and: 'a winner already inside it'
        def winner = new Thread({ OrderedExit.closeAndStopLogging() }, 'winner')
        winner.start()
        closeEntered.await(5, TimeUnit.SECONDS)

        when: 'the loser calls in and is released only after the winner finishes'
        def loserReturned = new CountDownLatch(1)
        def loser = new Thread({
            OrderedExit.closeAndStopLogging()
            loserReturned.countDown()
        }, 'loser')
        loser.start()

        then: 'it does not return while the teardown is still running'
        !loserReturned.await(200, TimeUnit.MILLISECONDS)

        when:
        releaseClose.countDown()

        then: 'it returns only once the flush has happened'
        loserReturned.await(5, TimeUnit.SECONDS)
        steps == ['closeContext', 'stopLogging']

        cleanup:
        winner.join(5_000)
        loser.join(5_000)
    }

    // NFR-R1: the release comes from a finally, so a teardown that ends in an Error frees the
    // waiting caller too. Without it the loser would sit out the whole bound before exiting — the
    // JVM stalling on the one failure whose log lines matter most.
    def "NFR-R1: the caller that loses the guard is released even when the teardown throws"() {
        given:
        def closeEntered = new CountDownLatch(1)
        def releaseClose = new CountDownLatch(1)
        OrderedExit.install({
            closeEntered.countDown()
            releaseClose.await(5, TimeUnit.SECONDS)
            throw new NoClassDefFoundError('the closing bean class went missing')
        }, {
            steps << 'stopLogging'
        })
        def winner = new Thread({
            try {
                OrderedExit.closeAndStopLogging()
            } catch (Throwable ignored) {
                steps << 'winnerThrew'
            }
        }, 'winner')
        winner.start()
        closeEntered.await(5, TimeUnit.SECONDS)

        and: 'a loser waiting on the sequence'
        def loserReturned = new CountDownLatch(1)
        def loser = new Thread({
            OrderedExit.closeAndStopLogging()
            loserReturned.countDown()
        }, 'loser')
        loser.start()
        !loserReturned.await(200, TimeUnit.MILLISECONDS)

        when:
        releaseClose.countDown()

        then: 'the flush happened, the Error still reached its own caller, and the loser is free'
        loserReturned.await(5, TimeUnit.SECONDS)
        steps == ['stopLogging', 'winnerThrew']

        cleanup:
        winner.join(5_000)
        loser.join(5_000)
    }

    // NFR-R1: the wait ends on an interrupt instead of holding the caller for the whole bound —
    // and the flag is restored, because whoever runs next on this thread is entitled to see it.
    def "NFR-R1: an interrupt ends the wait and is restored"() {
        given:
        def closeEntered = new CountDownLatch(1)
        def releaseClose = new CountDownLatch(1)
        OrderedExit.install({
            closeEntered.countDown()
            releaseClose.await(5, TimeUnit.SECONDS)
        }, {
            steps << 'stopLogging'
        })
        def winner = new Thread({ OrderedExit.closeAndStopLogging() }, 'winner')
        winner.start()
        closeEntered.await(5, TimeUnit.SECONDS)

        and: 'a loser blocked on the winner it will never outlast'
        def stillInterrupted = new AtomicBoolean()
        def loserReturned = new CountDownLatch(1)
        def loser = new Thread({
            OrderedExit.closeAndStopLogging()
            stillInterrupted.set(Thread.currentThread().isInterrupted())
            loserReturned.countDown()
        }, 'loser')
        loser.start()
        !loserReturned.await(200, TimeUnit.MILLISECONDS)

        when:
        loser.interrupt()

        then: 'it stops waiting at once, carrying the interrupt forward'
        loserReturned.await(5, TimeUnit.SECONDS)
        stillInterrupted.get()

        cleanup:
        releaseClose.countDown()
        winner.join(5_000)
        loser.join(5_000)
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
