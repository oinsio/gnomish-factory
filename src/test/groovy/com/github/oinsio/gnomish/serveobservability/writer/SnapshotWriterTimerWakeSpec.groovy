package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * Boundary coverage for {@link SnapshotWriter}'s {@code awaitNextWake} timer path,
 * split from {@link SnapshotWriterSpec} to stay under the file-size cap. No other
 * spec drives a purely timer-driven re-wake — the lifecycle specs all trip the
 * dirty flag — so the {@code remainingMillis <= 0} guard's boundary is otherwise
 * unexercised.
 *
 * <p>With a sub-millisecond interval, {@code (deadlineNanos - now) / 1_000_000}
 * truncates to exactly {@code 0} on the first entry — the {@code <=} boundary. The
 * real guard breaks and lets the loop take its next timer tick; the mutated {@code
 * remainingMillis < 0} instead falls through to {@code lock.wait(0)}, an UNBOUNDED
 * wait, hanging the writer after its first tick. Asserting that further timer ticks
 * arrive (no {@code markDirty}) makes the two observably different.
 *
 * <p>Implements FR1 of add-serve-observability.
 */
// Bound every feature so a dropped wake/stop mutant fails fast (red assertion) rather than hanging
// into a PIT TIMED_OUT, which the mutation gate counts as a failure rather than a kill.
@Timeout(10)
class SnapshotWriterTimerWakeSpec extends Specification {

    @TempDir
    Path tempDir

    def mapper = new SnapshotJsonMapper()

    // FR1: absent any dirty trigger, the timer beat must keep waking the writer. A
    // sub-ms interval forces remainingMillis == 0 on the awaitNextWake entry (the
    // exact <= boundary). Under <=, the guard breaks and tick() runs again — the
    // supplier self-stops the writer on its third call. Under the mutated <, the
    // guard falls through to wait(0) and blocks forever, so the count never reaches
    // three and the poll below times out (PIT also records the hang as a timeout).
    def "the timer beat keeps waking the writer for further ticks with no dirty trigger"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        SnapshotWriter writer
        writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() >= 3) {
                writer.stop()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofNanos(999_000), Clock.systemUTC(), 0)

        when:
        writer.start()

        then: 'purely timer-driven wakes reach the third tick, then the writer stops cleanly'
        new PollingConditions(timeout: 5).eventually {
            assert calls.get() >= 3
        }
        new PollingConditions(timeout: 2).eventually {
            assert !writer.worker().isAlive()
        }

        cleanup:
        writer.stop()
    }

    // FR1, D4: markDirty() must WAKE a writer already asleep on a long timer, not merely set the
    // dirty flag — so wake()'s lock.notifyAll() (VoidMethodCall target) is asserted by its observable
    // effect: a second write lands within a second, far inside the 10s timer that would otherwise be
    // the only wake. Bounded poll + interrupt cleanup: the assertion fails fast (never hangs) if the
    // notify is dropped, and the worker is always torn down without waiting out the timer.
    def "markDirty wakes a writer asleep on a long timer for a prompt extra write"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        // Self-stop past the real count (1 then 2) so a busy-spin awaitNextWake mutant caps its runaway
        // writes rather than looping through the poll windows under full-suite PIT load.
        SnapshotWriter writer
        writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() > 8) {
                writer.stop()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofSeconds(10), Clock.systemUTC(), 0)

        when: 'started, the immediate first write lands and the worker then sleeps on the 10s timer'
        writer.start()

        then:
        new PollingConditions(timeout: 2).eventually { assert calls.get() == 1 }

        when: 'markDirty is signalled while the worker sleeps'
        writer.markDirty()

        then: 'the worker is woken at once for a second write, well before the 10s timer'
        new PollingConditions(timeout: 1).eventually { assert calls.get() == 2 }

        cleanup:
        writer.stop()
        writer.worker()?.interrupt()
        writer.worker()?.join(2000)
    }

    // FR4: stop() must WAKE the sleeping worker (its wake() call is the VoidMethodCall target), not
    // just clear running — otherwise the thread lingers until the next timer beat. Asserts the worker
    // exits within a second of stop() despite the 10s interval. Bounded poll + interrupt cleanup so a
    // dropped wake fails the assertion fast rather than timing the whole run out.
    def "stop wakes a writer asleep on a long timer so it terminates promptly"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        def calls = new AtomicInteger()
        // Self-stop past the real count so a busy-spin awaitNextWake mutant caps its runaway writes.
        SnapshotWriter writer
        writer = new SnapshotWriter(target, {
            ->
            if (calls.incrementAndGet() > 8) {
                writer.stop()
            }
            SnapshotWriterSpec.fixtureSnapshot()
        }, mapper, Duration.ofSeconds(10), Clock.systemUTC(), 0)
        writer.start()
        new PollingConditions(timeout: 2).eventually { assert calls.get() == 1 }

        when: 'stop() is called while the worker sleeps on the 10s timer'
        writer.stop()

        then: 'the worker is woken and exits well before the timer would have fired'
        new PollingConditions(timeout: 1).eventually {
            assert !writer.worker().isAlive()
        }

        cleanup:
        writer.worker()?.interrupt()
        writer.worker()?.join(2000)
    }
}
