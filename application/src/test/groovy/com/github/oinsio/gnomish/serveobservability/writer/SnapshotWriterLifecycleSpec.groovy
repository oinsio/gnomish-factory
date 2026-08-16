package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@link SnapshotWriter#start}/{@link SnapshotWriter#loop}: the real writer
 * thread, proving the two-trigger/one-write-point contract of design D4 (FR1) —
 * a timer beat on its own keeps writing, an explicit {@link
 * SnapshotWriter#markDirty} wakes it well before a long timer would ever fire,
 * and a burst of rapid triggers coalesces rather than producing one write per
 * trigger — plus that a dirty-triggered write, arriving off the timer boundary,
 * still carries a fresh, accurate {@code writtenAt} (FR2).
 *
 * <p>Implements FR1, FR2 of add-serve-observability.
 */
@Timeout(10)
class SnapshotWriterLifecycleSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()
    def conditions = new PollingConditions(timeout: 3)

    // FR1, D4: the writer performs its first write right at startup, before any
    // beat or trigger — proven by an observable effect of tick(), not by calling
    // tick() directly.
    def "writes immediately at startup, before the first timer beat"() {
        given:
        def calls = new AtomicInteger()
        def writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'), {
                    -> calls.incrementAndGet(); SnapshotWriterSpec.fixtureSnapshot()
                },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)

        when:
        writer.start()

        then:
        conditions.eventually { assert calls.get() >= 1 }

        cleanup:
        writer.stop()
    }

    // FR1, D4: with no dirty trigger at all, the timer beat alone keeps producing
    // writes on its own — a short interval proves the beat, not just the startup
    // write.
    def "the timer beat alone produces further writes with no dirty trigger"() {
        given:
        def calls = new AtomicInteger()
        def writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'), {
                    -> calls.incrementAndGet(); SnapshotWriterSpec.fixtureSnapshot()
                },
                mapper,
                Duration.ofMillis(30),
                Clock.systemUTC(),
                0)

        when:
        writer.start()

        then:
        conditions.eventually { assert calls.get() >= 3 }

        cleanup:
        writer.stop()
    }

    // FR1, D4: markDirty() wakes the writer immediately rather than waiting for the
    // timer — proven by a deliberately long interval (far longer than the test
    // timeout) so any write beyond the startup one can only be explained by the
    // dirty-flag wake.
    def "markDirty triggers a prompt write without waiting for the timer"() {
        given:
        def calls = new AtomicInteger()
        // Self-stop after a bound the real (sleeping) writer never reaches: an awaitNextWake mutant
        // that busy-spins instead of waiting would otherwise write in a tight loop for the poll's
        // whole timeout, and under full-suite PIT load that runaway I/O surfaces as a TIMED_OUT/
        // MEMORY_ERROR rather than the fast red assertion below. The bound caps the spin at a few
        // ticks so the mutant dies as a clean kill.
        SnapshotWriter writer
        writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'), {
                    -> if (calls.incrementAndGet() > 8) {
                        writer.stop()
                    }; SnapshotWriterSpec.fixtureSnapshot()
                },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)
        writer.start()
        conditions.eventually { assert calls.get() == 1 }

        when: 'a transition marks the writer dirty'
        writer.markDirty()

        then: 'the extra write lands quickly, long before the 30s timer ever could'
        new PollingConditions(timeout: 1).eventually { assert calls.get() == 2 }

        cleanup:
        writer.stop()
    }

    // FR1, D4 Risks: many rapid triggers must not each produce their own write — the
    // single boolean dirty flag coalesces a burst into at most one extra write.
    def "a burst of rapid dirty triggers coalesces into a bounded number of writes"() {
        given:
        def calls = new AtomicInteger()
        // Self-stop past the real coalesced count so a busy-spin awaitNextWake mutant caps its runaway
        // writes at a few ticks (fast red kill) instead of looping for the settle window under load.
        SnapshotWriter writer
        writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'), {
                    -> if (calls.incrementAndGet() > 8) {
                        writer.stop()
                    }; SnapshotWriterSpec.fixtureSnapshot()
                },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)
        writer.start()
        conditions.eventually { assert calls.get() == 1 }

        when: '50 triggers land in a tight burst'
        50.times { writer.markDirty() }

        then: 'settle, then the call count is nowhere near one write per trigger'
        new PollingConditions(timeout: 1, delay: 0.2).eventually {
            assert calls.get() >= 2
        }
        Thread.sleep(300)
        calls.get() <= 3

        cleanup:
        writer.stop()
    }

    // FR2: a dirty-triggered write, landing well off the timer boundary, must still
    // carry an accurate, fresh writtenAt — not a stale value cached from the last
    // timer beat or from supplier-call time. A long interval (far longer than the
    // test) means the only write after startup can be the dirty-triggered one, and
    // its writtenAt (read back from disk) must fall after the moment markDirty()
    // was called.
    def "a dirty-triggered write off the timer boundary carries a fresh, accurate writtenAt"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def writer = new SnapshotWriter(
                target,
                { -> SnapshotWriterSpec.fixtureSnapshot() },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)
        writer.start()
        conditions.eventually { assert Files.exists(target) }

        when: 'the timer has not fired again; only an explicit dirty trigger causes the next write'
        def beforeTrigger = Instant.now()
        Thread.sleep(20)
        writer.markDirty()

        then: 'the resulting file content is fresh, timestamped after the trigger — not a stale reuse'
        conditions.eventually {
            assert writtenAtIn(target).isAfter(beforeTrigger)
        }

        cleanup:
        writer.stop()
    }

    private static Instant writtenAtIn(Path target) {
        def matcher = Files.readString(target) =~ /"writtenAt"\s*:\s*"([^"]+)"/
        return Instant.parse(matcher[0][1] as String)
    }

    // FR1: stop() must not leave the thread running indefinitely — it wakes and
    // exits promptly rather than waiting out the (possibly very long) interval.
    def "stop() wakes and ends the thread promptly rather than waiting out the interval"() {
        given:
        def writer = new SnapshotWriter(
                tempDir.resolve('snapshot.json'),
                { -> SnapshotWriterSpec.fixtureSnapshot() },
                mapper,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                0)
        writer.start()
        conditions.eventually { assert writer.worker() != null }

        when:
        writer.stop()
        writer.worker().join(2000)

        then:
        !writer.worker().isAlive()
    }
}
