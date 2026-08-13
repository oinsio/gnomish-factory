package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * NFR-R3 / FR11 regression: a SIGTERM arriving while the feed sits in an outage retry against a
 * down tracker must STOP the retry loop, not spin it hot until the JVM dies.
 *
 * <p>The production {@code ThreadSleeper} does not throw on interruption — it re-sets the interrupt
 * flag and returns immediately. So if {@link FeedOutageRetry#run} ignored that flag, the backoff
 * sleep would return at once, the still-down tracker would throw again, and the loop would busy-spin
 * forever. This spec reproduces exactly that shape with a sleeper that behaves like {@code
 * ThreadSleeper}-after-interruption (sets the flag on the first backoff, then returns), and asserts
 * the retry converts the pending interrupt into an {@link InterruptedException} — the same stop
 * signal {@code SlotLedger.acquire()} raises, which {@code ServeShutdownWiring.runFeedLoop} catches
 * to end the feed thread cleanly.
 *
 * <p>Implements NFR-R3, FR11 of add-factory-serve.
 */
class FeedOutageRetryInterruptSpec extends Specification {

    // FR11: interruption during an outage retry surfaces as InterruptedException, not a busy-spin.
    //     The @Timeout is the safety net: a regression that ignores the flag spins forever, and the
    //     "spin guard" sleeper below turns that into a fast IllegalStateException rather than a hang.
    @Timeout(10)
    def "an interrupt arriving during an outage retry stops the loop with InterruptedException, not a busy-spin"() {
        given: 'a sleeper that behaves like ThreadSleeper after interruption: the first backoff sets the interrupt flag and returns at once'
        def sleeps = new AtomicInteger()
        Sleeper interruptingSleeper = { Duration d ->
            int n = sleeps.incrementAndGet()
            if (n == 1) {
                Thread.currentThread().interrupt() // SIGTERM lands mid-backoff
            } else if (n> 3) {
                // Reached only if the interrupt flag was ignored and the loop kept spinning.
                throw new IllegalStateException('busy-spin: outage retry ignored the interrupt flag')
            }
        } as Sleeper

        and: 'a tracker call that never recovers within the shutdown window'
        def attempts = new AtomicInteger()
        def retry = new FeedOutageRetry(interruptingSleeper, {
            Duration.ofSeconds(30)
        })

        when: 'the outage retry runs against the perpetually-failing call while the interrupt arrives'
        retry.run('feed poll', {
            -> attempts.incrementAndGet(); throw new RuntimeException('tracker down')
        })

        then: 'it stops promptly by propagating the interrupt as the shutdown stop signal'
        thrown(InterruptedException)

        and: 'it did not spin: exactly one backoff, and the flag was consumed by Thread.interrupted()'
        sleeps.get() == 1
        !Thread.currentThread().isInterrupted()
    }
}
