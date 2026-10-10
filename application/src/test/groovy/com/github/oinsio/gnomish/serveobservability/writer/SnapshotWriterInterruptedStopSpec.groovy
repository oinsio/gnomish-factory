package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@link SnapshotWriter#stopAfterFinalWrite}: an {@link InterruptedException} while joining the
 * worker thread of the supervised loop must neither abort the final write nor cut the join short — the join
 * still waits the worker's tick out, so the final {@code stopped} write is the last one on disk
 * (FR7 of supervise-daemon-loops-and-embed-dashboard), and the calling thread's interrupt status is
 * restored (never swallowed). The join is the supervised loop's ({@code SupervisedLoop#stopAndJoin},
 * design D4 of supervise-daemon-loops-and-embed-dashboard); a stray interrupt of the writer's own
 * thread is {@link SnapshotWriterSupervisionSpec}'s subject.
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
    // exercised deterministically. The worker's pinned tick read the `running` state before the
    // stop; the tick is released only once the caller is back inside its join (or has returned), so
    // a join cut short by the interrupt lets that stale write land after the final one.
    def "an interrupted join still waits out the tick in progress, so the final write is the last, and the flag is restored"() {
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
                return SnapshotWriterSpec.fixtureSnapshot()
            }
            SnapshotWriterSpec.stoppedSnapshot()
        }, mapper, Duration.ofSeconds(30), VirtualTimeEquipment.create(), 0)
        writer.start()
        assert firstCallStarted.await(2, TimeUnit.SECONDS) // worker now pinned mid-tick
        def restored = new AtomicBoolean()

        when: 'an interrupted caller stops while the worker is provably still alive'
        def caller = Thread.ofPlatform().start {
            Thread.currentThread().interrupt()
            writer.stopAfterFinalWrite()
            restored.set(Thread.currentThread().isInterrupted())
        }
        new PollingConditions(timeout: 2).eventually {
            assert caller.state in [
                Thread.State.WAITING,
                Thread.State.TERMINATED
            ]
        }
        releaseTick.countDown()
        caller.join(2000)
        worker.join(2000)

        then: 'the caller returned only after the tick, and the final stopped write is the last on disk'
        !caller.alive
        !worker.alive
        new SnapshotJsonReader().read(Files.readString(target)).lifecycle() instanceof LifecycleState.Stopped

        and: 'the interrupt status was restored on the caller, not swallowed'
        restored.get()

        cleanup:
        releaseTick.countDown()
        worker?.join(2000)
    }
}
