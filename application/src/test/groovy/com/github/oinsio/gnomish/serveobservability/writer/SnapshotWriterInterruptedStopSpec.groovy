package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link SnapshotWriter#stopAfterFinalWrite}: an {@link InterruptedException} while joining the
 * background worker thread must not abort the final write — the calling thread's interrupt
 * status is restored (never swallowed) and the final synchronous write still happens (FR4). The
 * join is the supervised loop's ({@code SupervisedLoop#stopAndJoin}, design D4 of
 * supervise-daemon-loops-and-embed-dashboard); a stray interrupt of the writer's own thread is
 * {@link SnapshotWriterSupervisionSpec}'s subject.
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
        Thread worker = null
        def firstCallStarted = new CountDownLatch(1)
        def releaseTick = new CountDownLatch(1)
        // Only the worker's first tick blocks (pinning it alive); stopAfterFinalWrite's own final
        // writeOnce() re-invokes the supplier and must NOT block, or the method would never return.
        def writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() == 1) {
                worker = Thread.currentThread()
                firstCallStarted.countDown()
                releaseTick.await()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), VirtualTimeEquipment.create(), 0)
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
        worker?.join(2000)
    }
}
