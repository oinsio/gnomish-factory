package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * SlotLedger: the semaphore-backed slot capacity primitive (design D1) — permit-before-claim
 * ordering, the same-instance double-assignment guard, and the release-wakes-a-waiter local
 * slot-freed event. The concurrency specs drive real virtual threads with randomized scheduling
 * (no fixed interleaving order imposed) rather than sleeps, per FR1's "under randomized
 * interleavings" scenario.
 *
 * Implements FR1, D1, NFR-R1, M2 of add-factory-serve.
 */
class SlotLedgerSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')

    def "rejects a non-positive slot count"() {
        when:
        new SlotLedger(slots)

        then:
        thrown(IllegalArgumentException)

        where:
        slots << [0, -1]
    }

    def "a fresh ledger has all N permits free"() {
        expect:
        new SlotLedger(3).freeSlots() == 3
    }

    // FR1, D1: acquire spends a permit up front; assign ties it to the claimed task.
    def "acquire then assign occupies one permit"() {
        given:
        def ledger = new SlotLedger(2)

        when:
        ledger.acquire()
        ledger.assign(A)

        then:
        ledger.freeSlots() == 1
    }

    // FR1: the same-instance "never two slots for one task" guard.
    def "assigning an already-occupied task is rejected"() {
        given:
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(A)

        when: 'a second slot tries to claim the same task'
        ledger.acquire()
        ledger.assign(A)

        then:
        thrown(IllegalStateException)
    }

    // D1: release frees the permit and clears occupancy so the same ref can be assigned again later.
    def "release frees the permit and clears occupancy"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)

        when:
        ledger.release(A)

        then:
        ledger.freeSlots() == 1

        when: 'A is claimed again in a fresh slot'
        ledger.acquire()
        ledger.assign(A)

        then:
        noExceptionThrown()
    }

    def "releasing a task that does not occupy a slot is rejected"() {
        given:
        def ledger = new SlotLedger(1)

        when:
        ledger.release(A)

        then:
        thrown(IllegalStateException)
    }

    // D1: abandon returns an acquired-but-unassigned permit (a failed claim attempt) without
    //     touching occupancy.
    def "abandon returns an unassigned permit without requiring occupancy"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()

        when:
        ledger.abandon()

        then:
        ledger.freeSlots() == 1
    }

    // D1: the local slot-freed event — a thread parked in acquire() (Full state, no timer) is
    //     woken by a release() elsewhere, with no polling involved.
    def "release wakes a thread blocked in acquire"() {
        given: 'a fully occupied single-slot ledger'
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)

        and: 'a virtual thread parks in acquire() for the next free slot'
        def acquired = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        executor.submit({
            ledger.acquire()
            acquired.countDown()
        })

        expect: 'the waiter has not been woken while no permit is free'
        !acquired.await(200, TimeUnit.MILLISECONDS)

        when: 'the occupying task reaches its terminal result'
        ledger.release(A)

        then: 'the parked acquire() unblocks promptly, with no poll involved'
        acquired.await(2, TimeUnit.SECONDS)

        cleanup:
        executor.close()
    }

    // FR10: drain mode's wait primitive — awaitDrained() blocks while any permit is still
    //     outstanding (a slot running) and returns once every slot has released, without
    //     requiring any further acquire() calls to happen.
    def "awaitDrained blocks while a permit is outstanding and returns once it is released"() {
        given: 'a single-slot ledger with its one permit held by a running slot'
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)

        and: 'a virtual thread waits for the ledger to fully drain'
        def drained = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        executor.submit({
            ledger.awaitDrained()
            drained.countDown()
        })

        expect: 'the waiter has not returned while the slot is still occupied'
        !drained.await(200, TimeUnit.MILLISECONDS)

        when: 'the occupying slot reaches its terminal result'
        ledger.release(A)

        then: 'awaitDrained returns promptly'
        drained.await(2, TimeUnit.SECONDS)

        cleanup:
        executor.close()
    }

    // FR10: awaitDrained hands every permit right back, so freeSlots reads N afterwards — it
    //     is a wait, not a permanent acquisition.
    def "awaitDrained returns all permits to the pool once it unblocks"() {
        given:
        def ledger = new SlotLedger(3)

        when:
        ledger.awaitDrained()

        then:
        ledger.freeSlots() == 3
    }

    // FR10, NFR-O2, M3: property spec — under randomized interleaving, awaitDrained() only
    //     ever unblocks once no slot is running, mirroring the "claim attempts never exceed
    //     free slots" property spec above but for the drain barrier itself.
    def "awaitDrained never unblocks while any slot is still running under randomized interleaving"() {
        given:
        int slots = 4
        def ledger = new SlotLedger(slots)
        def refs = (0..<slots).collect { new TaskRef("github:o/r#${it}" as String) }
        refs.each { ledger.acquire(); ledger.assign(it) }
        def running = new AtomicInteger(slots)
        def sawZeroBeforeDrain = new AtomicBoolean(false)
        def drained = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def random = new Random(7)

        when: 'a waiter starts watching for the drain barrier while slots release one by one'
        executor.submit({
            ledger.awaitDrained()
            sawZeroBeforeDrain.set(running.get() == 0)
            drained.countDown()
        })
        refs.each {
            Thread.sleep(random.nextInt(5))
            running.decrementAndGet()
            ledger.release(it)
        }
        boolean finished = drained.await(5, TimeUnit.SECONDS)
        executor.close()

        then:
        finished
        sawZeroBeforeDrain.get()
    }

    // FR1, NFR-R1, M2: property spec — under randomized-interleaving concurrent load, claim
    //     attempts in flight (acquire..assign) never exceed the free-slot count N, and no task
    //     is ever assigned to two slots at once. Many virtual threads race a small pool of task
    //     refs; SlotLedger's own guards (the semaphore bound and the assign() occupancy check)
    //     are the mechanism under test, observed here through an independent running-count tally.
    def "claim attempts never exceed free slots and no task is ever double-assigned under randomized interleaving"() {
        given:
        int slots = 4
        int taskCount = 8
        int workersPerTask = 5
        def ledger = new SlotLedger(slots)
        def refs = (0..<taskCount).collect { new TaskRef("github:o/r#${it}" as String) }

        def running = new AtomicInteger(0)
        def maxObservedRunning = new AtomicInteger(0)
        // Per-ref concurrent-holder tally: independent of SlotLedger's own bookkeeping, this
        // directly measures "no task is ever assigned to two slots at once" by counting how many
        // workers are simultaneously inside the assigned section for the SAME ref.
        def perRefHolders = (0..<taskCount).collect { new AtomicInteger(0) }
        def maxObservedPerRefHolders = new AtomicInteger(0)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(taskCount * workersPerTask)
        def random = new Random(42)

        when: 'many virtual threads race to acquire+assign the same small set of task refs'
        (0..<taskCount).each { taskIndex ->
            (0..<workersPerTask).each {
                executor.submit({
                    start.await()
                    Thread.sleep(random.nextInt(5))
                    ledger.acquire()
                    boolean assigned = false
                    try {
                        ledger.assign(refs[taskIndex])
                        assigned = true
                    } catch (IllegalStateException expectedRace) {
                        // another worker already holds this same ref's slot right now — the guard
                        // did its job; this worker simply lost the race for this ref.
                    }
                    if (assigned) {
                        int now = running.incrementAndGet()
                        maxObservedRunning.updateAndGet { current -> Math.max(current, now) }
                        int perRefNow = perRefHolders[taskIndex].incrementAndGet()
                        maxObservedPerRefHolders.updateAndGet { current -> Math.max(current, perRefNow) }
                        Thread.sleep(random.nextInt(5))
                        perRefHolders[taskIndex].decrementAndGet()
                        running.decrementAndGet()
                        ledger.release(refs[taskIndex])
                    } else {
                        ledger.abandon()
                    }
                    done.countDown()
                })
            }
        }
        start.countDown()
        boolean finished = done.await(10, TimeUnit.SECONDS)
        executor.close()

        then: 'every worker finished (no deadlock/livelock)'
        finished

        and: 'no more tasks ran concurrently than there were slots (permit-before-claim bound)'
        maxObservedRunning.get() <= slots

        and: 'no ref was ever held by two slots at once (no double assignment)'
        maxObservedPerRefHolders.get() <= 1

        and: 'every permit made it back to the pool — no leak from a losing or winning path'
        ledger.freeSlots() == slots
    }

    // FR11, D9: a fresh (nothing occupied) ledger's occupiedRefs() is empty.
    def "occupiedRefs is empty on a fresh ledger"() {
        expect:
        new SlotLedger(2).occupiedRefs().isEmpty()
    }

    // FR11, D9: occupiedRefs reflects assign/release accurately, and is a snapshot (mutating the
    //     ledger afterwards does not change a set already returned).
    def "occupiedRefs reflects assign and release, and is an independent snapshot"() {
        given:
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(A)

        expect:
        ledger.occupiedRefs() == [A] as Set

        when:
        def snapshotWithOneOccupied = ledger.occupiedRefs()
        ledger.acquire()
        ledger.assign(B)

        then: 'the earlier snapshot is untouched by the later assign'
        snapshotWithOneOccupied == [A] as Set
        ledger.occupiedRefs() == [A, B] as Set

        when:
        ledger.release(A)

        then:
        ledger.occupiedRefs() == [B] as Set

        cleanup:
        ledger.release(B)
    }

    // FR11, D9: the SIGTERM grace-window wait — returns true once every slot frees within the
    //     bounded timeout, exactly like the unbounded awaitDrained().
    def "awaitDrained(Duration) returns true once every slot frees within the timeout"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)
        def executor = Executors.newVirtualThreadPerTaskExecutor()

        when:
        executor.submit({
            Thread.sleep(20)
            ledger.release(A)
        })
        boolean allFree = ledger.awaitDrained(Duration.ofSeconds(2))

        then:
        allFree
        ledger.freeSlots() == 1

        cleanup:
        executor.close()
    }

    // FR11, D9: a slot that never releases within the bounded timeout — awaitDrained(Duration)
    //     times out and reports false rather than blocking forever, and hands every acquirable
    //     permit straight back (a wait, not a permanent acquisition, exactly like the unbounded
    //     method).
    def "awaitDrained(Duration) times out and returns false when a slot never releases in time"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)

        when:
        boolean allFree = ledger.awaitDrained(Duration.ofMillis(100))

        then:
        !allFree
        ledger.freeSlots() == 0

        cleanup:
        ledger.release(A)
    }
}
