package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@link SnapshotWriter#stopAfterFinalWrite}: an {@link InterruptedException} while joining the
 * background worker thread must not abort the final write — the calling thread's interrupt
 * status is restored (never swallowed) and the final synchronous write still happens (FR4).
 *
 * <p>Implements FR4 of add-serve-observability.
 */
@Timeout(10)
class SnapshotWriterInterruptedStopSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()

    // Thread.join() throws InterruptedException only if the joined thread is still ALIVE when the
    // (already-interrupted) caller enters join — a dead thread makes join() return without ever
    // calling wait(), so the catch (and its interrupt-restore) would never run. A supplier blocked
    // on a latch pins the worker mid-tick, guaranteeing it is alive at join() and the catch path is
    // exercised deterministically (otherwise the interrupt-restore mutant flakily survives).
    def "still performs the final write when the join is interrupted, and restores the interrupt flag"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        def firstCallStarted = new CountDownLatch(1)
        def releaseTick = new CountDownLatch(1)
        // Only the worker's first tick blocks (pinning it alive); stopAfterFinalWrite's own final
        // writeOnce() re-invokes the supplier and must NOT block, or the method would never return.
        def writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() == 1) {
                firstCallStarted.countDown()
                releaseTick.await()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), Clock.systemUTC(), 0)
        writer.start()
        assert firstCallStarted.await(2, TimeUnit.SECONDS) // worker now pinned mid-tick

        when: 'the caller is interrupted and stops while the worker is provably still alive'
        Thread.currentThread().interrupt()
        writer.stopAfterFinalWrite()

        then: 'the final write still landed on disk'
        Files.exists(target)

        and: 'the interrupt status was restored on this thread, not swallowed'
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted() // clear the flag so it doesn't leak into other tests
        releaseTick.countDown()
        writer.worker()?.join(2000)
    }

    // The background loop's own wait — awaitNextWake()'s lock.wait(remainingMillis) — must
    // also tolerate an interrupt without dying: it restores the interrupt flag and returns,
    // and the outer loop() keeps running rather than exiting (FR1: only stop() ends the loop).
    def "the worker thread keeps looping after its wait is interrupted mid-sleep"() {
        given:
        def calls = new AtomicInteger()
        def writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'),
                { -> calls.incrementAndGet(); SnapshotWriterSpec.fixtureSnapshot() },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)
        writer.start()
        new PollingConditions(timeout: 3).eventually { assert calls.get() >= 1 }
        Thread.sleep(50) // let the worker settle into its long lock.wait()

        when: 'the worker thread is interrupted while asleep, well before the 30s timer'
        writer.worker().interrupt()

        then: 'the loop survives the interrupt and keeps ticking rather than exiting'
        new PollingConditions(timeout: 3).eventually { assert calls.get() >= 2 }

        cleanup:
        writer.stop()
        writer.worker()?.join(2000)
    }
}
