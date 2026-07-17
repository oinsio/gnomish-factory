package com.github.oinsio.gnomish.adapter.engine

import java.time.Duration
import spock.lang.Specification

/**
 * D10, M2 of add-manual-run: a plain direct spec for the production {@link Sleeper}
 * environment-port adapter. No abstract port-contract ceremony — {@code Sleeper} is a
 * one-method blocking wrapper and there is exactly one production implementation to
 * assert against.
 */
class ThreadSleeperSpec extends Specification {

    def "sleep(duration) blocks for approximately the requested duration"() {
        given:
        def sleeper = new ThreadSleeper()
        def requested = Duration.ofMillis(30)

        when:
        def start = System.nanoTime()
        sleeper.sleep(requested)
        def elapsedMs = (System.nanoTime() - start) / 1_000_000

        then: 'elapsed time is at least the requested duration, generously bounded above'
        elapsedMs >= requested.toMillis()
        elapsedMs < requested.toMillis() + 2000
    }

    def "interruption during sleep re-sets the thread's interrupt flag"() {
        given:
        def sleeper = new ThreadSleeper()
        def interruptedAfterSleep = false

        when:
        def worker = new Thread({
            sleeper.sleep(Duration.ofSeconds(5))
            interruptedAfterSleep = Thread.currentThread().isInterrupted()
        })
        worker.start()
        Thread.sleep(50)
        worker.interrupt()
        worker.join(2000)

        then: 'sleep() returned promptly (interrupted, not the full 5s) and the flag was re-set'
        !worker.isAlive()
        interruptedAfterSleep
    }
}
