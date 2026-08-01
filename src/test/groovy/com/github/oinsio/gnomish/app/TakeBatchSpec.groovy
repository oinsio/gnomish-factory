package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.TakeResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * {@link TakeBatch}: the generic scheduler loop batch take runs its ref list through (task 6.2 of
 * add-factory-serve) — order preservation, skip-and-continue, and the N-slot concurrency bound.
 * Deliberately independent of {@link TakeDispatcher}'s collaborators (tracker, git, pipeline): the
 * scheduling concern is proven here with a plain {@code Function<String, TakeResult>} stand-in for
 * one ref's disposition, exactly per the seam this class is built around.
 *
 * FR3 of add-factory-serve.
 */
class TakeBatchSpec extends Specification {

    // FR3: every ref runs, in order, and a Skipped result is reported like any other — "skips
    // reported and the run continues" falls out of the loop treating every TakeResult the same.
    def "runs every ref and returns one outcome per ref, in the original order"() {
        given:
        def refs = ['a', 'b', 'c']
        def perRef = { String ref ->
            ref == 'b' ? new TakeResult.Skipped('b refused') : new TakeResult.Delivered(null, "$ref delivered")
        }

        when:
        def outcomes = TakeBatch.run(refs, 3, perRef)

        then:
        outcomes*.ref() == ['a', 'b', 'c']
        outcomes[0].result() instanceof TakeResult.Delivered
        outcomes[1].result() instanceof TakeResult.Skipped
        (outcomes[1].result() as TakeResult.Skipped).reason() == 'b refused'
        outcomes[2].result() instanceof TakeResult.Delivered
    }

    // FR3: a single-slot ledger serializes every ref — proves the loop actually spends a permit
    // per ref rather than firing them all at once regardless of N.
    def "a single slot runs refs one at a time"() {
        given:
        def refs = ['a', 'b']
        def concurrent = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def perRef = { String ref ->
            int now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { Math.max(it, now) }
            Thread.sleep(50)
            concurrent.decrementAndGet()
            new TakeResult.Delivered(null, "$ref delivered")
        }

        when:
        TakeBatch.run(refs, 1, perRef)

        then:
        maxConcurrent.get() == 1
    }

    // FR3, design D1 (reused structurally from serve's SlotLedger): concurrency is bounded to N —
    // deterministic via a latch rather than sleeps, mirroring SlotLedgerSpec's own style. Two refs
    // block on a shared latch (occupying both of a 2-slot ledger); a third ref must not start until
    // one of the first two releases its slot.
    def "bounds concurrency to N — a third ref waits for a free slot"() {
        given:
        def refs = ['a', 'b', 'c']
        def bothBlockedStarted = new CountDownLatch(2)
        def releaseBlocked = new CountDownLatch(1)
        def started = new ConcurrentLinkedQueue<String>()
        def perRef = { String ref ->
            started.add(ref)
            if (ref == 'a' || ref == 'b') {
                bothBlockedStarted.countDown()
                releaseBlocked.await(5, TimeUnit.SECONDS)
            }
            new TakeResult.Delivered(null, "$ref delivered")
        }

        when: 'the batch is driven on its own thread — run() blocks until every ref finishes'
        def batchThread = Thread.ofVirtual().start { TakeBatch.run(refs, 2, perRef) }

        then: 'both slot-occupying refs started, but the third has not — no free slot yet'
        bothBlockedStarted.await(2, TimeUnit.SECONDS)
        !started.contains('c')

        when: 'one of the two occupying refs releases its slot'
        releaseBlocked.countDown()
        batchThread.join(2000)

        then: 'the third ref ran once a slot freed, and the batch completed'
        started.contains('c')
        !batchThread.isAlive()
    }

    // FR3, NFR-O2, tracker-take spec "Tool failure dominates": an uncaught RuntimeException from
    // one ref's perRef call is captured as that ref's own tool-failure outcome instead of killing
    // its virtual thread silently (which would previously leave that slot's outcome as null and
    // NPE downstream) — the run continues and every other ref still gets a real outcome.
    def "an uncaught RuntimeException from one ref is captured as a tool-failure outcome, and the run continues"() {
        given:
        def refs = ['a', 'b', 'c']
        def perRef = { String ref ->
            if (ref == 'b') {
                throw new UsageException('b is malformed')
            }
            new TakeResult.Delivered(null, "$ref delivered")
        }

        when:
        def outcomes = TakeBatch.run(refs, 3, perRef)

        then:
        outcomes*.ref() == ['a', 'b', 'c']
        outcomes[0].result() instanceof TakeResult.Delivered
        outcomes[2].result() instanceof TakeResult.Delivered

        and: 'the failing ref carries a tool failure, not a null result'
        outcomes[1].result() == null
        outcomes[1].toolFailure() != null
        outcomes[1].exitCode() == 2
        outcomes[1].describe().contains('b is malformed')
    }

    // Edge: an interrupted wait for a free slot propagates rather than being swallowed.
    def "an interrupted wait for a free slot propagates InterruptedException"() {
        given:
        def refs = ['a', 'b']
        def blockForever = { String ref -> Thread.sleep(60_000); new TakeResult.Delivered(null, 'never') }
        def caught = null
        def blockedThread = Thread.ofVirtual().start {
            try {
                TakeBatch.run(refs, 1, blockForever)
            } catch (InterruptedException e) {
                caught = e
            }
        }

        when:
        Thread.sleep(100) // let the first ref start occupying the single slot
        blockedThread.interrupt()
        blockedThread.join(2000)

        then:
        caught != null
    }
}
