package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.testsupport.StepClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@link SnapshotWriter#tick}: the single write point, driven directly with no
 * thread and no waiting (mirroring {@code WorktreeJanitorSpec}'s {@code tick()}
 * seam) — proves the supplier is consulted and the result lands atomically on
 * disk, that a write failure never escapes (NFR-R1), and that the writer — not
 * the supplier — stamps {@code writtenAt}/{@code intervalSeconds} with the
 * actual write-time clock and configured interval on every write (FR2).
 *
 * <p>Implements FR1, FR2 of add-serve-observability.
 */
// Bound every feature (matching SnapshotWriterLifecycleSpec/InterruptedStopSpec): the real-thread
// stopAfterFinalWrite/interrupt tests must fail fast, never hang, so a broken wake/stop mutant
// surfaces as a red assertion within budget rather than a PIT TIMED_OUT (which the mutation gate
// treats as a failure, not a kill).
@Timeout(10)
class SnapshotWriterSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()

    def "tick() obtains the current snapshot from the supplier, stamps writtenAt/intervalSeconds from the writer's own clock and interval, and writes the serialized form"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def snapshot = fixtureSnapshot()
        def writeInstant = Instant.parse('2026-08-02T10:15:00Z')
        def clock = Clock.fixed(writeInstant, ZoneOffset.UTC)
        def writer = new SnapshotWriter(target, {
            -> snapshot
        }, mapper, Duration.ofSeconds(45), clock, 0)

        when:
        writer.tick()

        then: 'the file reflects the stamped values, not whatever the supplier happened to carry'
        Files.readString(target) == mapper.serialize(snapshot.withSelfDescription(writeInstant, 45L))
    }

    // FR2: staleness must be computable from the file alone, which only holds if
    // every write — timer beat or dirty-triggered — carries the real moment of
    // that specific write. Two ticks against a clock that advances between them
    // must produce two different writtenAt values reflecting write time, not
    // whatever instant the supplier itself might have embedded.
    def "two consecutive ticks at different times produce different writtenAt values reflecting the actual write time"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def clock = new StepClock([
            Instant.parse('2026-08-02T10:00:00Z'),
            Instant.parse('2026-08-02T10:00:31Z')
        ])
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), clock, 0)

        when:
        writer.tick()
        def firstText = Files.readString(target)
        writer.tick()
        def secondText = Files.readString(target)

        then:
        firstText != secondText
        firstText.contains('2026-08-02T10:00:00Z')
        secondText.contains('2026-08-02T10:00:31Z')
    }

    // FR2: intervalSeconds must reflect the writer's configured interval so a
    // reader with only the file can size its staleness threshold correctly.
    def "intervalSeconds in the written file matches the writer's configured interval"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def clock = Clock.fixed(Instant.parse('2026-08-02T10:00:00Z'), ZoneOffset.UTC)
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(90), clock, 0)

        when:
        writer.tick()

        then:
        Files.readString(target).contains('"intervalSeconds" : 90')
    }

    def "tick() calls the supplier exactly once per tick, so content reflects the latest state"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        def writer = new SnapshotWriter(target, {
            -> calls.incrementAndGet(); fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), Clock.systemUTC(), 0)

        when:
        writer.tick()
        writer.tick()

        then:
        calls.get() == 2
    }

    // NFR-R1: an observability write failure must never propagate out of tick() and
    // crash the daemon. Forcing the target's parent to be an existing regular file
    // (not a directory) makes AtomicFileWriter's mkdir/move fail with an IOException.
    def "a write failure is swallowed rather than propagated"() {
        given:
        def blockingFile = tempDir.resolve('not-a-directory')
        Files.writeString(blockingFile, 'not a directory')
        def target = blockingFile.resolve('snapshot.json')
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), Clock.systemUTC(), 0)

        when:
        writer.tick()

        then:
        noExceptionThrown()
    }

    // FR15, design D7: the retention sweep shares the snapshot writer's tick, scanning
    // targetFile's parent directory for ledger-*.jsonl files older than the configured
    // retention.
    def "tick() sweeps stale ledger files older than the configured retention from the snapshot directory"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def staleLedger = tempDir.resolve('ledger-2026-01-01.jsonl')
        Files.writeString(staleLedger, 'stale')
        def clock = Clock.fixed(Instant.parse('2026-08-02T10:00:00Z'), ZoneOffset.UTC)
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), clock, 30)

        when:
        writer.tick()

        then: 'the snapshot write and the sweep both land on the same tick'
        Files.exists(target)
        !Files.exists(staleLedger)
    }

    // FR15, design D10: ledgerRetentionDays == 0 means "keep forever" — tick() must
    // never delete a ledger file regardless of age.
    def "tick() does not sweep any ledger file when retention is 0 (keep forever)"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def ancientLedger = tempDir.resolve('ledger-2020-01-01.jsonl')
        Files.writeString(ancientLedger, 'ancient')
        def clock = Clock.fixed(Instant.parse('2026-08-02T10:00:00Z'), ZoneOffset.UTC)
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), clock, 0)

        when:
        writer.tick()

        then:
        Files.exists(ancientLedger)
    }

    // Task 5.1, FR4: stopAfterFinalWrite() must guarantee the LAST bytes on disk reflect the
    // content at the moment it is called, even though the background thread is running against
    // a mutable supplier. A supplier flipping from 'running' to 'stopped' content the instant
    // this method is invoked proves the final write is not raced by a stray background tick.
    def "stopAfterFinalWrite() writes the content current at call time, after the background thread has fully stopped"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def stopped = new AtomicBoolean(false)
        def writer = new SnapshotWriter(target, {
            -> stopped.get() ? stoppedSnapshot() : fixtureSnapshot()
        }, mapper, Duration.ofMillis(20), Clock.systemUTC(), 0)
        writer.start()
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(target)
        }

        when:
        stopped.set(true)
        writer.stopAfterFinalWrite()

        then: 'the file on disk reflects the stopped content, not a stale running one'
        Files.readString(target).contains('"state" : "stopped"')

        and: 'the worker thread has actually terminated'
        !writer.worker().isAlive()
    }

    // Task 6.3, FR4: stopAfterFinalWrite() must WAIT for the background thread's in-flight tick
    // to fully finish (Thread::join) before performing its own synchronous final write — otherwise
    // the two writes race and the background thread's stale content can land AFTER the "final" one.
    // The supplier's first call blocks on a latch so the background thread is provably still mid-
    // tick when stopAfterFinalWrite() is invoked on another thread; if join() were removed, the
    // final write would happen immediately (supplier's 2nd, non-blocking call) — BEFORE the latch
    // is released — which the "file exists before release" assertion below would catch.
    def "stopAfterFinalWrite() waits for the background thread's in-flight tick to finish before writing"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        def firstCallStarted = new CountDownLatch(1)
        def releaseFirstCall = new CountDownLatch(1)
        def writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() == 1) {
                firstCallStarted.countDown()
                releaseFirstCall.await()
                return fixtureSnapshot()
            }
            return stoppedSnapshot()
        }, mapper, Duration.ofSeconds(30), Clock.systemUTC(), 0)
        writer.start()
        assert firstCallStarted.await(2, TimeUnit.SECONDS)

        when: 'stopAfterFinalWrite is invoked while the background thread is still blocked mid-tick'
        def stopper = Thread.ofVirtual().start { writer.stopAfterFinalWrite() }
        Thread.sleep(150) // let stopAfterFinalWrite reach the join() call and start blocking on it
        boolean fileExistedBeforeRelease = Files.exists(target)
        releaseFirstCall.countDown()
        stopper.join(2000)

        then: 'no write happened until the background thread was released and finished (proves join())'
        !stopper.isAlive()
        !fileExistedBeforeRelease

        and: 'the final bytes on disk are the stopped content, not overwritten by a stray background write'
        Files.readString(target).contains('"state" : "stopped"')
    }

    // Task 6.3, FR1: awaitNextWake's InterruptedException catch must restore the interrupt status
    // (Thread.currentThread().interrupt()) rather than swallowing it — Object#wait() itself clears
    // the flag as part of throwing, so only the explicit restore leaves it set afterward. Driven by
    // directly interrupting the parked worker thread (the only way to reach this catch); the
    // supplier's second call self-stops the writer so the loop terminates deterministically right
    // after, with nothing further touching the thread's interrupt status.
    def "awaitNextWake restores the interrupt status after being interrupted while waiting"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        SnapshotWriter writer
        writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() == 2) {
                writer.stop()
            }
            fixtureSnapshot()
        }, mapper, Duration.ofSeconds(10), Clock.systemUTC(), 0)
        writer.start()

        when: 'wait until the worker thread is genuinely parked waiting for its next tick'
        new PollingConditions(timeout: 2).eventually {
            assert writer.worker().getState() == Thread.State.TIMED_WAITING
        }

        and: 'interrupt it directly — the only way to drive the InterruptedException catch path'
        writer.worker().interrupt()

        and: 'wait for the thread to run its self-stopping second tick and terminate'
        new PollingConditions(timeout: 2).eventually {
            assert !writer.worker().isAlive()
        }

        then: 'the interrupt status survived to thread termination — restored by the catch, not lost'
        writer.worker().isInterrupted()
    }

    def "stopAfterFinalWrite() throws if the writer was never started"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def writer = new SnapshotWriter(target, {
            -> fixtureSnapshot()
        }, mapper, Duration.ofSeconds(30), Clock.systemUTC(), 0)

        when:
        writer.stopAfterFinalWrite()

        then:
        thrown(IllegalStateException)
    }

    static Snapshot stoppedSnapshot() {
        def base = fixtureSnapshot()
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                new LifecycleState.Stopped('sigterm'), base.feed(), base.slots(), base.vitals(), base.tracker())
    }

    static Snapshot fixtureSnapshot() {
        def instance = new InstanceInfo('gnomish-factory-x7k2q1', 'worker-1.internal', '0.1.0-SNAPSHOT')
        def feed = new FeedSnapshot(FeedPhase.FILLING, Instant.parse('2026-08-02T08:59:50Z'), Instant.parse('2026-08-02T08:59:55Z'), 2, 3)
        def slots = new SlotsSnapshot(3, [])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.RUNNING, Instant.parse('2026-08-02T08:59:58Z'), 0),
                new ReaperVital(Instant.parse('2026-08-02T08:55:00Z'), 0, 300L),
                new JanitorVital(Instant.parse('2026-08-02T08:00:00Z')))
        def tracker = new TrackerHealth(Instant.parse('2026-08-02T08:59:55Z'), 0)
        return new Snapshot(1, Instant.parse('2026-08-02T09:00:00Z'), 30L, instance, new LifecycleState.Running(), feed, slots, vitals, tracker)
    }
}
