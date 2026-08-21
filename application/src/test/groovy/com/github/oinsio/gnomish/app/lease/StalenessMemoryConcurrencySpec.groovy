package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification

/**
 * StalenessMemory under concurrent access: the standing reaper's own thread drives
 * observe/forgetAll/retryEmission while the sandbox-lifecycle tick thread reads staleRefs()
 * through LivenessOracle. Both threads exist in the serve daemon simultaneously
 * (StandingReaper#loop and SandboxLifecycleTick#loop), so the memory must tolerate it: an
 * unsynchronized HashMap would throw ConcurrentModificationException out of staleRefs() —
 * killing the sweep tick — or hand the liveness oracle a torn read that feeds the destructive
 * "unowned" verdict.
 *
 * FR3 of add-serve-sandbox-lifecycle; NFR-R1.
 */
class StalenessMemoryConcurrencySpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Instant ANCIENT = Instant.parse('2000-01-01T00:00:00Z')
    private static final int ROUNDS = 3000

    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)

    private static OpenTask working(String ref, ClaimVersion version) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'), version, 'fixture title')
    }

    private static ClaimVersion version(int beat) {
        new ClaimVersion('marker-1', ANCIENT.plusSeconds(beat))
    }

    // FR3, NFR-R1: staleRefs() is read from the sweep thread while the reaper thread mutates the
    //     memory — it must never throw and never observe a broken map.
    def "staleRefs read concurrently with observe never throws"() {
        given: 'a listing whose membership churns every round, forcing structural map changes'
        def start = new CountDownLatch(1)
        def readerFailure = new AtomicReference<Throwable>()
        def writerFailure = new AtomicReference<Throwable>()
        def reads = 0

        def writer = Thread.start {
            start.await()
            try {
                (1..ROUNDS).each { round ->
                    // membership churn: half the refs drop out each round -> retainAll + put
                    def listing = (0..<20)
                    .findAll { (it + round) % 2 == 0 }
                    .collect { working("T-$it", version(round)) }
                    time.advance(TTL)
                    def stale = memory.observe(listing)
                    stale.each { memory.retryEmission(it) }
                    if (round % 100 == 0) {
                        memory.forgetAll()
                    }
                }
            } catch (Throwable e) {
                writerFailure.set(e)
            }
        }

        def reader = Thread.start {
            start.await()
            try {
                while (writer.alive) {
                    memory.staleRefs().each { assert it != null }
                    reads++
                }
            } catch (Throwable e) {
                readerFailure.set(e)
            }
        }

        when:
        start.countDown()
        writer.join(60_000)
        reader.join(60_000)

        then: 'neither side blew up, and the reader really ran against a live writer'
        writerFailure.get() == null
        readerFailure.get() == null
        reads> 0
    }
}
