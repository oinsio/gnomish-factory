package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

    // Thread.join() throws InterruptedException immediately if the calling thread's interrupt
    // status is already set on entry — no timing race needed to hit this deterministically.
    def "still performs the final write when the join is interrupted, and restores the interrupt flag"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def writer = new SnapshotWriter(target, { -> SnapshotWriterSpec.fixtureSnapshot() }, mapper, Duration.ofSeconds(30), java.time.Clock.systemUTC(), 0)
        writer.start()

        when:
        Thread.currentThread().interrupt()
        writer.stopAfterFinalWrite()

        then: 'the final write still landed on disk'
        Files.exists(target)

        and: 'the interrupt status was restored on this thread, not swallowed'
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted() // clear the flag so it doesn't leak into other tests
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
                java.time.Clock.systemUTC(),
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
