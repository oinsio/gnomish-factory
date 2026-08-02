package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * NFR-R2, D8 of add-factory-serve: the per-clone lock component in isolation — per-clone keying,
 * a second operation against the SAME clone key blocks until the first releases, and operations
 * against DIFFERENT clone keys never block each other.
 */
class CloneMutationLockSpec extends Specification {

    def lock = new CloneMutationLock()

    def "runs the operation under lock and returns its result"() {
        expect:
        lock.runLocked(Path.of('/a-clone'), { 'result' }) == 'result'
    }

    def "a second operation against the same clone key waits for the first to finish, never overlapping"() {
        given:
        def key = Path.of('/same-clone')
        def maxConcurrent = new AtomicInteger(0)
        def concurrent = new AtomicInteger(0)
        def firstEntered = new CountDownLatch(1)
        def releaseFirst = new CountDownLatch(1)
        def secondStarted = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when: 'the first operation takes the lock and holds it open'
        def first = executor.submit({
            lock.runLocked(key, {
                int now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { current -> Math.max(current, now) }
                firstEntered.countDown()
                releaseFirst.await()
                concurrent.decrementAndGet()
                'first'
            })
        } as Callable)
        firstEntered.await(2, TimeUnit.SECONDS)

        and: 'a second operation against the SAME key is submitted while the first still holds it'
        def second = executor.submit({
            lock.runLocked(key, {
                secondStarted.countDown()
                int now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { current -> Math.max(current, now) }
                concurrent.decrementAndGet()
                'second'
            })
        } as Callable)

        then: 'the second has not entered while the first still holds the lock'
        !secondStarted.await(200, TimeUnit.MILLISECONDS)

        when: 'the first releases'
        releaseFirst.countDown()

        then: 'both complete, and the two never ran concurrently'
        first.get(2, TimeUnit.SECONDS) == 'first'
        second.get(2, TimeUnit.SECONDS) == 'second'
        maxConcurrent.get() == 1

        cleanup:
        // Not close(): under a dropped-unlock mutant the second task is parked forever in the
        // non-interruptible lock.lock(), and close() would join it forever — the red second.get
        // timeout above has already killed the mutant, so cleanup must stay bounded (daemon
        // virtual threads are safe to orphan). See build.gradle's pitestVerifyAllKilled.
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    def "operations against different clone keys run concurrently, unblocked by each other"() {
        given:
        def keyA = Path.of('/clone-a')
        def keyB = Path.of('/clone-b')
        def bothEntered = new CountDownLatch(2)
        def release = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when: 'two operations against DIFFERENT clone keys both start, each held open'
        def a = executor.submit({
            lock.runLocked(keyA, {
                bothEntered.countDown()
                release.await()
                'a'
            })
        })
        def b = executor.submit({
            lock.runLocked(keyB, {
                bothEntered.countDown()
                release.await()
                'b'
            })
        })

        then: 'both entered without waiting on each other'
        bothEntered.await(2, TimeUnit.SECONDS)

        cleanup:
        release.countDown()
        a.get(2, TimeUnit.SECONDS)
        b.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
