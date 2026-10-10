package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.app.daemon.LatchedSleeper
import com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness
import com.github.oinsio.gnomish.dashboard.DaemonSnapshotView
import com.github.oinsio.gnomish.dashboard.SnapshotReader
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * The final {@code stopped} snapshot against a writer death (task 5.2 of
 * supervise-daemon-loops-and-embed-dashboard, design D4, D5, D8; M5), on real threads per {@code
 * lock-scope.md} "Specs". The death is real: the write cycle throws a failure whose own rendering
 * throws ({@link SupervisedLoopHarness.Unrenderable}), so the loop's guard cannot report it, the
 * worker thread ends and its death handler waits the respawn backoff in a {@link LatchedSleeper}
 * while the spec calls {@link SnapshotWriter#stopAfterFinalWrite()} from another thread. The
 * supplier is the counting writer: every write consults it exactly once, so its journal is the
 * sequence of writes. Also the serve-observability scenario "Writer death is not a dead daemon", on
 * virtual time.
 *
 * <p>Implements FR7 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(20)
class SnapshotWriterFinalWriteRaceSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofSeconds(30)
    private static final Duration PROMPT = Duration.ofSeconds(2)
    private static final Duration QUIET = Duration.ofMillis(300)

    @TempDir
    Path tempDir

    final List<String> writes = Collections.synchronizedList([])
    final AtomicBoolean stopping = new AtomicBoolean()
    final AtomicBoolean finalWriteReturned = new AtomicBoolean()
    final CountDownLatch lateWrite = new CountDownLatch(1)
    final reader = new SnapshotJsonReader()
    SnapshotWriter writer

    def cleanup() {
        writer?.stop()
    }

    // A writer whose second write kills its thread; every write is journaled, and a write after
    // stopAfterFinalWrite() returned opens lateWrite.
    private SnapshotWriter dyingWriter(Path target, LatchedSleeper backoff) {
        def calls = new AtomicInteger()
        new SnapshotWriter(target, {
            ->
            if (finalWriteReturned.get()) {
                lateWrite.countDown()
            }
            if (calls.incrementAndGet() == 2) {
                writes << 'died'
                throw new SupervisedLoopHarness.Unrenderable()
            }
            writes << (stopping.get() ? 'stopped' : 'running')
            stopping.get() ? SnapshotWriterSpec.stoppedSnapshot() : SnapshotWriterSpec.fixtureSnapshot()
        }, new SnapshotJsonMapper(), INTERVAL, VirtualTimeEquipment.on(new VirtualClock(), backoff), 0)
    }

    private Snapshot onDisk(Path target) {
        reader.read(Files.readString(target))
    }

    // The worker writes once, then a dirty trigger makes its next write kill it; returns once the
    // death handler sits in the latched respawn backoff.
    private void killWriterIntoBackoff(LatchedSleeper backoff) {
        writer.start()
        new PollingConditions(timeout: PROMPT.toSeconds()).eventually {
            assert writes == ['running']
        }
        writer.markDirty()
        assert backoff.awaitEntered()
    }

    private Thread finalWriteOnHelper() {
        stopping.set(true)
        Thread.ofVirtual().start {
            writer.stopAfterFinalWrite()
            finalWriteReturned.set(true)
        }
    }

    // FR7, M5 (serve-observability "Death racing the final write"): a stop that cuts the backoff
    //     short leaves the final write the last one, and returns without waiting the backoff out.
    def "FR7, M5: a final write during a respawn backoff the stop cuts short is the last write"() {
        given: 'a writer whose thread died and whose respawn waits in a backoff a stop can cut short'
        def target = tempDir.resolve('snapshot.json')
        def backoff = new LatchedSleeper(true)
        writer = dyingWriter(target, backoff)
        killWriterIntoBackoff(backoff)

        when: 'the shutdown begins during the backoff'
        def finalWrite = finalWriteOnHelper()

        then: 'stopAfterFinalWrite() returned promptly, the backoff cut short'
        finalWrite.join(PROMPT)
        backoff.interrupted

        and: 'the file ends on the stopped record, the final write being the last of all'
        onDisk(target).lifecycle() instanceof LifecycleState.Stopped
        writes == ['running', 'died', 'stopped']

        and: 'no respawned worker writes afterwards, not even on a dirty trigger'
        backoff.release.countDown()
        writer.markDirty()
        !lateWrite.await(QUIET.toMillis(), TimeUnit.MILLISECONDS)
        writes == ['running', 'died', 'stopped']
    }

    // FR7, M5 (design D4, D5): the backoff runs to its end after the stop began — the handler's
    //     phase 3 must see the stop — and the final write waits for the dead thread's handler first.
    def "FR7, M5: a final write racing a backoff that outlives the stop is still the last write"() {
        given: 'a writer whose thread died and whose respawn waits in a backoff no stop can cut short'
        def target = tempDir.resolve('snapshot.json')
        def backoff = new LatchedSleeper(false)
        writer = dyingWriter(target, backoff)
        killWriterIntoBackoff(backoff)

        when: 'the shutdown begins during the backoff, and the backoff ends only afterwards'
        def finalWrite = finalWriteOnHelper()
        new PollingConditions(timeout: PROMPT.toSeconds()).eventually {
            assert backoff.interrupted
        }
        boolean finalBeforeBackoffEnded = !finalWrite.isAlive()
        def contentBeforeBackoffEnded = writes.toList()
        backoff.release.countDown()

        then: 'the final write waited for the death handler, then ran'
        !finalBeforeBackoffEnded
        contentBeforeBackoffEnded == ['running', 'died']
        finalWrite.join(PROMPT)

        and: 'the file ends on the stopped record, and no respawn wrote after it'
        onDisk(target).lifecycle() instanceof LifecycleState.Stopped
        writer.markDirty()
        !lateWrite.await(QUIET.toMillis(), TimeUnit.MILLISECONDS)
        writes == ['running', 'died', 'stopped']
    }

    // FR7 (serve-observability "Writer death is not a dead daemon"): the first backoff after a clean
    //     run is one interval (Unbounded(interval), capped at MAX_BACKOFF), so the respawned worker's
    //     startup write lands within two intervals of the death on the writer's own virtual clock,
    //     and the dashboard classifies the snapshot as fresh at that moment.
    def "FR7: a single writer death is followed by a write within two intervals on virtual time"() {
        given: 'a writer on a virtual clock that only the respawn backoff advances'
        def target = tempDir.resolve('snapshot.json')
        def clock = new VirtualClock(Instant.parse('2026-10-09T10:00:00Z'))
        def calls = new AtomicInteger()
        def diedAt = new AtomicReference<Instant>()
        writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() == 2) {
                diedAt.set(clock.instant())
                throw new SupervisedLoopHarness.Unrenderable()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, new SnapshotJsonMapper(), INTERVAL, VirtualTimeEquipment.on(clock), 0)
        writer.start()
        new PollingConditions(timeout: PROMPT.toSeconds()).eventually {
            assert Files.exists(target)
        }

        when: 'the writer dies once while the daemon serves'
        writer.markDirty()

        then: 'a new snapshot is written after the death'
        new PollingConditions(timeout: PROMPT.toSeconds()).eventually {
            assert calls.get() == 3
            assert onDisk(target).writtenAt().isAfter(diedAt.get())
        }

        and: 'within two snapshot intervals of the death'
        Duration.between(diedAt.get(), onDisk(target).writtenAt()) <= INTERVAL.multipliedBy(2)
        onDisk(target).lifecycle() instanceof LifecycleState.Running

        and: 'the dashboard, reading two intervals after the death, shows no dead-daemon alarm'
        new SnapshotReader().read(target, diedAt.get() + INTERVAL.multipliedBy(2)) instanceof DaemonSnapshotView.Fresh
    }
}
