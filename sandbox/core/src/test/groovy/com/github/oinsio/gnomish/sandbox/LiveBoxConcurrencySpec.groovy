package com.github.oinsio.gnomish.sandbox

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification

/**
 * FR21, M9 of make-checkpoint-gate-durable (D13): a materialize runs with the live box's monitor
 * released (`lock-scope.md`) — a reader never waits for it, a same-key request joins the one
 * build, every other writer waits on the build's future, never on the monitor. Real threads and a
 * materializer blocked on a latch, the {@code RemoteOutageGateProbeConcurrencySpec} model; {@code
 * LiveBoxSpec} owns the single-threaded contract. A thread waits on the build once it parks
 * ({@code WAITING}); every wait here is bounded.
 */
class LiveBoxConcurrencySpec extends Specification {

    private static final Duration PROMPTLY = Duration.ofSeconds(2)
    private static final Duration GENEROUSLY = Duration.ofSeconds(30)

    private final List<String> events = new CopyOnWriteArrayList<>()
    private final CountDownLatch buildEntered = new CountDownLatch(1)
    private final CountDownLatch releaseBuild = new CountDownLatch(1)
    private final AtomicInteger built = new AtomicInteger()
    private final List<Worker> workers = []
    private Throwable buildFailure = null
    private List<TaskExecutionEnvironment> boxes = (1..3).collect { int id ->
        Stub(TaskExecutionEnvironment) {
            dispose() >> { events << "dispose-${id}".toString() }
        }
    }

    /** The first build blocks until the spec releases it, then fails with buildFailure if set. */
    private LiveBox<String> live = new LiveBox<String>({
        boxes[built.getAndIncrement()]
    }, { env, key ->
        events << "materialize-${boxes.indexOf(env) + 1}:${key}".toString()
        if (buildEntered.count> 0) {
            buildEntered.countDown()
            releaseBuild.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)
            if (buildFailure != null) {
                throw buildFailure
            }
        }
    })

    /** An action on its own thread: its value or throwable, and its interrupt flag at the end. */
    private final class Worker {
        final AtomicReference<Object> outcome = new AtomicReference<>()
        volatile boolean interruptedAtEnd
        final Thread thread

        Worker(Closure action) {
            workers << this
            thread = Thread.ofPlatform().start {
                try {
                    outcome.set(action())
                } catch (Throwable failure) {
                    outcome.set(failure)
                }
                interruptedAtEnd = Thread.currentThread().isInterrupted()
            }
        }
    }

    /** The build of key 'a', in flight on its own thread and blocked in materialize. */
    private Worker buildInFlight() {
        def owner = new Worker({ live.environmentFor('a') })
        assert buildEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)
        owner
    }

    /** A worker parked behind the build in flight. */
    private Worker parked(Closure action) {
        def waiter = new Worker(action)
        def deadline = System.nanoTime() + GENEROUSLY.toNanos()
        while (waiter.thread.state != Thread.State.WAITING) {
            assert System.nanoTime() <deadline: "never parked: ${waiter.thread.state}"
            Thread.onSpinWait()
        }
        waiter
    }

    /** Releases the blocked build and waits out every worker; also the per-feature cleanup. */
    def cleanup() {
        releaseBuild.countDown()
        workers.each { it.thread.join(GENEROUSLY.toMillis()) }
    }

    // FR21, M9: a reader never waits out a materialize — the build runs with the monitor released
    def "current() returns promptly, and empty, while a build blocks"() {
        given:
        buildInFlight()

        when:
        def reader = new Worker({ live.current() })
        reader.thread.join(PROMPTLY.toMillis())

        then:
        !reader.thread.alive
        reader.outcome.get() == Optional.empty()
    }

    // FR21: a second request for the key in flight joins the one build — one materialize
    def "a same-key request joins the build in flight"() {
        given:
        def owner = buildInFlight()
        def joiner = parked { live.environmentFor('a') }

        when:
        cleanup()

        then:
        owner.outcome.get().is(boxes[0])
        joiner.outcome.get().is(boxes[0])
        events == ['materialize-1:a']
    }

    // FR21: a failed build clears its claim and releases every waiter with its own failure
    def "a failed build releases every waiter with the failure: #kind"() {
        given:
        buildFailure = failure
        def owner = buildInFlight()
        def joiner = parked { live.environmentFor('a') }

        when:
        cleanup()

        then:
        owner.outcome.get().is(failure)
        joiner.outcome.get().is(failure)
        live.current().isEmpty()

        and: 'the claim was cleared: the next request builds again'
        live.environmentFor('a').is(boxes[1])

        where:
        kind | failure
        'a runtime error' | new IllegalStateException('self-check failed')
        'an Error' | new AssertionError('box build died')
    }

    // FR21, D13: a request for another key waits for the build to settle, then decides afresh
    def "another key's request waits for the build, then retires its box"() {
        given:
        def owner = buildInFlight()
        def other = parked { live.environmentFor('b') }

        when:
        cleanup()

        then:
        owner.outcome.get().is(boxes[0])
        other.outcome.get().is(boxes[1])
        events.join(' ') == 'materialize-1:a dispose-1 materialize-2:b'
    }

    // FR21: dispose never tears a box down mid-materialization — it waits, then disposes once
    def "dispose during a build waits for it, then disposes the built box once"() {
        given:
        buildInFlight()
        def disposer = parked { live.dispose(); 'disposed' }

        expect: 'nothing is disposed while the build blocks'
        events == ['materialize-1:a']

        when:
        cleanup()

        then:
        disposer.outcome.get() == 'disposed'
        events.join(' ') == 'materialize-1:a dispose-1'
        live.current().isEmpty()
    }

    // lock-scope.md: an interrupted waiter leaves at once, with its interrupt flag restored
    def "an interrupted wait gives up promptly and restores the flag: #waiter"() {
        given:
        buildInFlight()
        def waiting = parked { action(live) }

        when:
        waiting.thread.interrupt()
        waiting.thread.join(PROMPTLY.toMillis())

        then: 'it left while the build still blocks'
        !waiting.thread.alive
        waiting.outcome.get() instanceof IllegalStateException
        waiting.interruptedAtEnd

        where:
        waiter | action
        'same key' | { LiveBox<String> l -> l.environmentFor('a') }
        'another key' | { LiveBox<String> l -> l.environmentFor('b') }
        'dispose' | { LiveBox<String> l -> l.dispose() }
    }
}
