package com.github.oinsio.gnomish.logtext

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * {@link ShutdownPhase}: the flag that lets a death caused by the daemon's own stop be told apart
 * from a spontaneous one. Without it a clean SIGTERM ends a healthy run with a burst of ERROR
 * lines that name no fault.
 *
 * <p>FR9 of harden-logging-observability.
 */
class ShutdownPhaseSpec extends Specification {

    def setup() {
        ShutdownPhase.reset()
    }

    def cleanup() {
        ShutdownPhase.reset()
    }

    def "FR9: the phase has not begun until begin is called"() {
        expect:
        !ShutdownPhase.inProgress()
    }

    def "FR9: begin marks the phase as in progress"() {
        when:
        ShutdownPhase.begin()

        then:
        ShutdownPhase.inProgress()
    }

    def "FR9: begin is idempotent — a second hook pass changes nothing"() {
        given:
        ShutdownPhase.begin()

        when:
        ShutdownPhase.begin()

        then:
        ShutdownPhase.inProgress()
    }

    // The readers run on their own threads (a slot's virtual thread, a heartbeat worker, a git
    // subprocess caller) while the flag is set on the shutdown-hook thread. The field being
    // volatile is what makes that publication real rather than lucky, so it is asserted across a
    // genuine thread boundary and not merely on the setting thread.
    def "FR9: the flag set on one thread is visible to a reader on another"() {
        given:
        def observed = new CountDownLatch(1)
        boolean seen = false
        def reader = Thread.ofVirtual().unstarted {
            while (!ShutdownPhase.inProgress()) {
                Thread.onSpinWait()
            }
            seen = true
            observed.countDown()
        }

        when:
        reader.start()
        ShutdownPhase.begin()

        then:
        observed.await(5, TimeUnit.SECONDS)
        seen
    }

    def "reset clears the phase so a spec JVM can drive many stops"() {
        given:
        ShutdownPhase.begin()

        when:
        ShutdownPhase.reset()

        then:
        !ShutdownPhase.inProgress()
    }
}
