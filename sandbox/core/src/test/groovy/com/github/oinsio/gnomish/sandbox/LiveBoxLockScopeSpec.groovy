package com.github.oinsio.gnomish.sandbox

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR21 of make-checkpoint-gate-durable (D13), `lock-scope.md`: the live box's monitor guards its
 * record only. Every blocking step of a build — the previous box's dispose, the factory call, the
 * materialize — and the dispose of {@link LiveBox#dispose()} run with the monitor released, so a
 * reader asking for the current box returns at once. Real threads and a dispose blocked on a latch,
 * the {@code LiveBoxConcurrencySpec} model; every wait here is bounded.
 */
class LiveBoxLockScopeSpec extends Specification {

    private static final Duration PROMPTLY = Duration.ofSeconds(2)
    private static final Duration GENEROUSLY = Duration.ofSeconds(30)

    private final CountDownLatch disposeEntered = new CountDownLatch(1)
    private final CountDownLatch releaseDispose = new CountDownLatch(1)
    private final List<String> lockHeldDuring = [].asSynchronized()
    private LiveBox<String> live

    /** A box whose dispose records whether the monitor is held, then blocks until released. */
    private TaskExecutionEnvironment blockingBox() {
        Stub(TaskExecutionEnvironment) {
            dispose() >> {
                if (Thread.holdsLock(live)) {
                    lockHeldDuring << 'dispose'
                }
                disposeEntered.countDown()
                releaseDispose.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)
            }
        }
    }

    private Optional<TaskExecutionEnvironment> currentWithin(Duration budget) {
        CompletableFuture.supplyAsync {
            live.current()
        }.get(budget.toMillis(), TimeUnit.MILLISECONDS)
    }

    // Scenario: sandbox/execution-environment / A reader never waits on a disposal in flight
    def "FR21: a reader asking for the current box returns at once while dispose() is blocked in the box's teardown"() {
        given: 'a recorded box whose teardown blocks'
        def box = blockingBox()
        live = new LiveBox<String>({ box }, { env, key -> })
        live.environmentFor('a')

        when: 'dispose() runs on its own thread and is blocked inside the box'
        def disposing = Thread.ofPlatform().start { live.dispose() }
        assert disposeEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)

        then: 'the reader returns promptly with what the record holds — nothing, the box already left it'
        currentWithin(PROMPTLY) == Optional.empty()

        cleanup:
        releaseDispose.countDown()
        disposing?.join(GENEROUSLY.toMillis())
    }

    // Scenario: sandbox/execution-environment / A reader never waits on a disposal in flight
    def "FR21: a reader returns at once while a key change is blocked disposing the previous box"() {
        given: 'box 1 recorded for key a, box 2 the next build'
        def first = blockingBox()
        def second = Stub(TaskExecutionEnvironment)
        def boxes = [first, second].iterator()
        live = new LiveBox<String>({ boxes.next() }, { env, key -> })
        live.environmentFor('a')

        when: 'a request for key b is blocked inside box 1’s dispose'
        def rebuilding = Thread.ofPlatform().start { live.environmentFor('b') }
        assert disposeEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)

        then: 'the reader is answered promptly, without waiting for the disposal'
        currentWithin(PROMPTLY) == Optional.empty()

        when: 'the disposal finishes'
        releaseDispose.countDown()
        rebuilding.join(GENEROUSLY.toMillis())

        then: 'the outcome was recorded under the lock again: the new box is current'
        live.current() == Optional.of(second)
    }

    // Scenario: sandbox/execution-environment / The live-box lock spans no Docker work and no git read
    def "FR21: no step that reaches Docker or git — previous dispose, factory, materialize, dispose — runs under the lock"() {
        given: 'a box per build, each step recording whether the live box’s monitor is held'
        def built = 0
        live = new LiveBox<String>({
            if (Thread.holdsLock(live)) {
                lockHeldDuring << 'factory'
            }
            built++
            Stub(TaskExecutionEnvironment) {
                dispose() >> {
                    if (Thread.holdsLock(live)) {
                        lockHeldDuring << 'dispose'
                    }
                }
            }
        }, { env, key ->
            if (Thread.holdsLock(live)) {
                lockHeldDuring << 'materialize'
            }
        })

        when: 'a box is built, replaced on a key change, and disposed'
        live.environmentFor('a')
        def replacement = live.environmentFor('b')
        def recordedAfterReplace = live.current()
        live.dispose()

        then: 'every blocking step ran with nothing held, and each outcome landed in the record'
        lockHeldDuring.isEmpty()
        built == 2
        recordedAfterReplace == Optional.of(replacement)
        live.current() == Optional.empty()
    }
}
