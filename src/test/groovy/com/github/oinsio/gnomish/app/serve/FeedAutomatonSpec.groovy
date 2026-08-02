package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * FeedAutomaton: the serve feed's four-state cycle (design D1) — Full as the ledger's own
 * block (zero polls, immediate wake), Filling's no-pause claim loop with claim-race fallthrough,
 * and the shared jittered idle interval split into Idle-empty vs Idle-blocked. Driven one {@code
 * step()} at a time (package-private, mirroring InstanceHeartbeat.tick()) with a VirtualSleeper/
 * VirtualClock so timing is deterministic and instant.
 *
 * Implements FR5, FR9, D1, D4 of add-factory-serve.
 */
class FeedAutomatonSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final int WIP_LIMIT = 2

    // Budgeted: a mutant that breaks a tracker call outright (instead of merely failing it) spins
    // FeedOutageRetry's deliberate retry-forever loop instantly on a virtual sleeper; the budget
    // fails the spec instead of hanging it (see BudgetedVirtualSleeper's Javadoc).
    private final VirtualClock clock = new VirtualClock()
    private final VirtualSleeper sleeper = new BudgetedVirtualSleeper(clock)

    // A Random whose picks are always index/fraction zero: the head-zone pick keeps the
    // original ordering and the idle jitter adds nothing, making candidate order and slept
    // durations exact rather than randomized.
    private static final class FixedRandom extends Random {
        @Override
        int nextInt(int bound) {
            0
        }

        @Override
        double nextDouble() {
            0.0d
        }
    }

    private static ReadyTask fresh(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false)
    }

    private static SlotRunner capturing(List<TaskRef> sink) {
        { TaskRef ref -> sink.add(ref) } as SlotRunner
    }

    // startSlot (FeedCycle) hands the claimed ref to the SlotRunner on a freshly spawned virtual
    // thread (design D1) — step()/drain() return before that thread necessarily runs, so a test
    // asserting on `sink`'s content must wait for it to reach the expected size instead of reading
    // it immediately, or the assertion races the virtual thread's scheduling (usually wins, but not
    // guaranteed — e.g. under PIT's instrumented, CPU-contended coverage/mutation runs).
    private static void awaitSize(List<TaskRef> sink, int expectedSize) {
        new PollingConditions(timeout: 2).eventually {
            assert sink.size() == expectedSize
        }
    }

    private FeedAutomaton automaton(Tracker tracker, SlotLedger ledger, SlotRunner runner, Random random, int wipLimit = WIP_LIMIT) {
        new FeedAutomaton(tracker, INSTANCE, ledger, runner, sleeper, clock, BASE, CAP, IDLE, wipLimit, random)
    }

    // Non-termination guard: drain() and repeated step() park forever in SlotLedger's unbounded
    // acquire()/awaitDrained() under a permit-accounting mutant (a dropped abandon/assign/release
    // or startSlot call, a negated drain empty-poll check) — PIT could only report such a hang as
    // TIMED_OUT, which pitestVerifyAllKilled rejects. Running the call on a bounded virtual thread
    // (interrupt surfaces at the next acquire/awaitDrained) turns it into a red assertion instead.
    private static void completesWithin(Duration bound = Duration.ofSeconds(5), Closure body) {
        def failure = new AtomicReference<Throwable>()
        def worker = Thread.ofVirtual().name('feed-under-test').start {
            try {
                body()
            } catch (Throwable t) {
                failure.set(t)
            }
        }
        if (!worker.join(bound)) {
            worker.interrupt()
            worker.join(Duration.ofSeconds(1))
            throw new AssertionError("feed call did not complete within ${bound} — non-termination (leaked permit or runaway loop)" as Object)
        }
        if (failure.get() != null) {
            throw failure.get()
        }
    }

    // Interrupt-based executor cleanup: close() joins still-running tasks forever, so a mutant
    // that leaves the submitted step()/drain() parked would hang the spec in cleanup even after
    // its assertions already failed — shutdownNow() interrupts the parked call instead.
    private static void interruptAndAwait(ExecutorService executor) {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    // FR5: Full is SlotLedger's own acquire() block — while every slot is occupied, a step()
    //     in flight sends zero tracker calls; the moment a slot frees, it proceeds without
    //     waiting for the idle timer (no sleeper invocation at all on this path).
    def "Full sends no tracker polls while occupied and unblocks immediately on release"() {
        given: 'a single-slot ledger already fully occupied'
        def busy = new TaskRef('github:o/r#busy')
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(busy)

        and: 'a tracker that records every listReady/listOpen call'
        def listReadyCalls = new AtomicInteger()
        def listOpenCalls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit -> listReadyCalls.incrementAndGet(); [] },
            listOpen : { -> listOpenCalls.incrementAndGet(); [] },
        ] as Tracker

        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def stepDone = new CountDownLatch(1)
        def observedState = new AtomicInteger(-1)
        executor.submit {
            def state = automaton.step()
            observedState.set(state.ordinal())
            stepDone.countDown()
        }

        expect: 'step() has not returned and made no tracker calls while parked in acquire()'
        !stepDone.await(200, TimeUnit.MILLISECONDS)
        listReadyCalls.get() == 0
        listOpenCalls.get() == 0

        when: 'the occupying slot reaches its terminal result'
        ledger.release(busy)

        then: 'the parked step() proceeds promptly and polls exactly once, with no wait for the idle timer'
        stepDone.await(2, TimeUnit.SECONDS)
        listReadyCalls.get() == 1
        listOpenCalls.get() == 1

        cleanup:
        interruptAndAwait(executor)
    }

    // FR5: Filling — a free slot and an eligible task claim and loop again with no pause;
    //     repeated cycles never touch the sleeper. Each cycle sees a fresh task id so a claim
    //     never collides with the previous cycle's still-occupied (or not-yet-released) slot.
    def "Filling claims repeatedly with no pause and never sleeps"() {
        given:
        def ledger = new SlotLedger(3)
        def counter = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit ->
                [
                    fresh("github:o/r#${counter.incrementAndGet()}" as String)
                ]
            },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        def automaton = automaton(tracker, ledger, capturing(claimed), new FixedRandom())

        when: 'three consecutive cycles each find a free slot and an eligible task'
        def states = (1..3).collect { automaton.step() }

        then:
        states == [
            FeedState.FILLING,
            FeedState.FILLING,
            FeedState.FILLING
        ]
        sleeper.slept.isEmpty()
    }

    // FR9: a claim-race loss (Held) falls through to the next candidate within the same cycle.
    def "a lost claim race falls through to the next candidate"() {
        given:
        def ledger = new SlotLedger(2)
        def lost = fresh('github:o/r#1')
        def won = fresh('github:o/r#2')
        def claimCalls = new CopyOnWriteArrayList<TaskRef>()
        Tracker tracker = [
            listReady: { int limit -> [lost, won] },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance ->
                claimCalls.add(ref)
                ref == lost.ref() ? new ClaimResult.Held('other-instance') : new ClaimResult.Acquired()
            },
        ] as Tracker
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        def automaton = automaton(tracker, ledger, capturing(claimed), new FixedRandom())

        when:
        def state = automaton.step()

        then:
        state == FeedState.FILLING
        claimCalls == [lost.ref(), won.ref()]
        awaitSize(claimed, 1)
        claimed == [won.ref()]
    }

    // FR5: Idle-empty — a free slot exists but the queue holds nothing backoff-eligible; the
    //     single idle interval is slept, jittered by up to +20% (design D4).
    def "Idle-empty polls, finds nothing, and sleeps the jittered idle interval"() {
        given:
        def ledger = new SlotLedger(1)
        Tracker tracker = [
            listReady: { int limit -> [] },
            listOpen : { -> [] },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        def state = automaton.step()

        then: 'and the permit reserved for the never-made claim went back to the pool, not leaked'
        state == FeedState.IDLE_EMPTY
        sleeper.slept == [IDLE]
        ledger.freeSlots() == 1
    }

    // FR5, FR6: Idle-blocked — backoff-eligible entries exist but are all fresh tasks blocked
    //     by the WIP limit (open fronts >= W) and no returned task is ready; still one shared
    //     idle interval.
    def "Idle-blocked polls, finds only WIP-blocked fresh tasks, and sleeps the same idle interval"() {
        given:
        def ledger = new SlotLedger(1)
        def blockedFresh = fresh('github:o/r#1')
        def openFronts = (1..WIP_LIMIT).collect {
            new OpenTask(new TaskRef("github:o/r#open-${it}" as String), new TrackerTaskState.Working('other'), null)
        }
        Tracker tracker = [
            listReady: { int limit -> [blockedFresh] },
            listOpen : { -> openFronts },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        def state = automaton.step()

        then: 'and the permit reserved for the never-made claim went back to the pool, not leaked'
        state == FeedState.IDLE_BLOCKED
        sleeper.slept == [IDLE]
        ledger.freeSlots() == 1
    }

    // Design D4: idle jitter adds a uniform 0-20% to the interval; assert the bound holds
    //     across several seeded draws rather than trusting a single fixed pick.
    def "idle jitter stays within the configured 0-20% window across seeds"() {
        given:
        Tracker tracker = [
            listReady: { int limit -> [] },
            listOpen : { -> [] },
        ] as Tracker

        expect:
        seeds.every { seed ->
            // A fresh single-slot ledger per seed: sharing one across the five step() calls would
            // park the second call forever in acquire() under a permit-leaking mutant.
            def localSleeper = new VirtualSleeper(new VirtualClock())
            def a = new FeedAutomaton(tracker, INSTANCE, new SlotLedger(1), capturing([]), localSleeper, clock, BASE, CAP, IDLE, WIP_LIMIT, new Random(seed))
            a.step()
            def slept = localSleeper.slept.first()
            slept.toNanos() >= IDLE.toNanos() && slept.toNanos() <= (IDLE.toNanos() * 1.2) as long
        }

        where:
        seeds << [[1L, 2L, 3L, 42L, 12345L]]
    }

    // FR10, M3: drain mode's stop-claiming signal — the very first empty candidate poll commits
    //     to stopping (no idle sleep, no re-poll); with N=2 slots and 3 eligible tasks (mirroring
    //     the "Nightly drain" scenario) every task still gets claimed and run before drain()
    //     returns, proving occupied slots finish even after claiming has already stopped.
    def "drain claims and runs every eligible task, then returns once all slots empty, with no sleep"() {
        given:
        def ledger = new SlotLedger(2)
        def remaining = new CopyOnWriteArrayList<>(['1', '2', '3'])
        Tracker tracker = [
            listReady: { int limit ->
                remaining.collect { fresh("github:o/r#${it}" as String) }
            },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance ->
                remaining.remove(ref.id().replace('github:o/r#', ''))
                new ClaimResult.Acquired()
            },
        ] as Tracker
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        def slotRunner = { TaskRef ref -> claimed.add(ref) } as SlotRunner
        def automaton = automaton(tracker, ledger, slotRunner, new FixedRandom())

        when:
        completesWithin { automaton.drain() }

        then:
        claimed.size() == 3
        claimed.collect { it.id() }.toSet() == (['1', '2', '3'].collect { "github:o/r#${it}" as String }).toSet()
        sleeper.slept.isEmpty()
        ledger.freeSlots() == 2
    }

    // M3: "--drain on an empty queue exits 0 with an empty-run report" — a queue that is empty
    //     from the very first poll claims nothing and returns immediately, no sleep either.
    def "drain on an empty queue returns immediately, claiming nothing"() {
        given:
        def ledger = new SlotLedger(2)
        def claimCalls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit -> [] },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance -> claimCalls.incrementAndGet(); new ClaimResult.Acquired() },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        completesWithin { automaton.drain() }

        then:
        claimCalls.get() == 0
        sleeper.slept.isEmpty()
        ledger.freeSlots() == 2
    }

    // FR10: once the poll observes an empty candidate list, drain commits to stopping — it does
    //     not re-poll even though a later poll would find newly-eligible work (a returned task
    //     appearing after the empty observation is deliberately not picked up: the plain reading
    //     of "nothing eligible to claim" as the one-shot stop signal, per the Nightly drain
    //     scenario's bounded-run framing).
    def "drain does not re-poll after the first empty observation even if work would appear later"() {
        given:
        def ledger = new SlotLedger(1)
        def pollCount = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit -> pollCount.incrementAndGet(); [] },
            listOpen : { -> [] },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        completesWithin { automaton.drain() }

        then:
        pollCount.get() == 1
    }

    // NFR-O1: attaches a ListAppender directly to FeedAutomaton's Logback logger (same pattern
    //     as LoggingLevelSpec) for the duration of one or more step() calls, then detaches it.
    private static List<ILoggingEvent> captureLogs(Closure body) {
        LogbackLogger logbackLogger = (LogbackLogger) LoggerFactory.getLogger(FeedAutomaton)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        Level original = logbackLogger.level
        logbackLogger.level = Level.DEBUG
        try {
            body()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
            logbackLogger.level = original
        }
        return appender.list
    }

    // NFR-O1: the WIP-blocked case must name the bottleneck explicitly — a concrete open-front
    //     count and the "not starting fresh work" framing — at a level an operator watching the
    //     daemon log actually sees (INFO), not DEBUG.
    def "Idle-blocked logs an INFO line naming the open-front count and that fresh work is not starting"() {
        given:
        def ledger = new SlotLedger(1)
        def blockedFresh = fresh('github:o/r#1')
        def openFronts = (1..WIP_LIMIT).collect {
            new OpenTask(new TaskRef("github:o/r#open-${it}" as String), new TrackerTaskState.Working('other'), null)
        }
        Tracker tracker = [
            listReady: { int limit -> [blockedFresh] },
            listOpen : { -> openFronts },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        List<ILoggingEvent> events = captureLogs { automaton.step() }
        def blockedEvents = events.findAll { it.level == Level.INFO && it.formattedMessage.contains('not starting fresh work') }

        then:
        blockedEvents.size() == 1
        blockedEvents[0].formattedMessage.contains(WIP_LIMIT as String)
    }

    // NFR-O1, UX2: logging the same Idle-blocked line on every idle-poll cycle (default 30s)
    //     forever would drown the log; the automaton logs the INFO line only on the transition
    //     into Idle-blocked, not on every subsequent cycle while it remains blocked.
    def "staying in Idle-blocked across repeated cycles logs the INFO line only once, on the transition"() {
        given:
        def ledger = new SlotLedger(1)
        def blockedFresh = fresh('github:o/r#1')
        def openFronts = (1..WIP_LIMIT).collect {
            new OpenTask(new TaskRef("github:o/r#open-${it}" as String), new TrackerTaskState.Working('other'), null)
        }
        Tracker tracker = [
            listReady: { int limit -> [blockedFresh] },
            listOpen : { -> openFronts },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when: 'three consecutive cycles all land in Idle-blocked'
        List<ILoggingEvent> events = captureLogs {
            // Bounded: under a permit-leaking mutant the second step() parks forever in acquire().
            completesWithin {
                automaton.step()
                automaton.step()
                automaton.step()
            }
        }
        def blockedEvents = events.findAll { it.level == Level.INFO && it.formattedMessage.contains('not starting fresh work') }

        then: 'only the first cycle (the Filling/initial -> Idle-blocked transition) logged the INFO line'
        blockedEvents.size() == 1
    }

    // NFR-O1: a transition from Filling into Idle-blocked is observably logged, distinguishing
    //     it from a run that starts (and stays) Idle-blocked from the first cycle.
    def "a Filling to Idle-blocked transition is logged when the cycle after a claim finds only WIP-blocked tasks"() {
        given:
        def ledger = new SlotLedger(2)
        def fillingCounter = new AtomicInteger()
        def openFronts = (1..WIP_LIMIT).collect {
            new OpenTask(new TaskRef("github:o/r#open-${it}" as String), new TrackerTaskState.Working('other'), null)
        }
        def blockedFresh = fresh('github:o/r#blocked')
        Tracker tracker = [
            listReady: { int limit ->
                fillingCounter.get() == 0 ? [fresh('github:o/r#1')] : [blockedFresh]
            },
            listOpen : { -> fillingCounter.get() == 0 ? [] : openFronts },
            claim    : { TaskRef ref, String instance ->
                fillingCounter.incrementAndGet()
                new ClaimResult.Acquired()
            },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when: 'the first cycle fills a slot, the second finds only WIP-blocked fresh tasks'
        List<ILoggingEvent> events = captureLogs {
            automaton.step()
            automaton.step()
        }
        def blockedEvents = events.findAll { it.level == Level.INFO && it.formattedMessage.contains('not starting fresh work') }

        then:
        blockedEvents.size() == 1
    }

    // NFR-R3: a sustained tracker outage hitting listReady during step()'s poll is caught,
    //     logged, and retried with the idle-interval backoff (no wall-clock wait, VirtualSleeper)
    //     until the tracker recovers, at which point the cycle proceeds normally — the exception
    //     never propagates out of step().
    def "step() retries a listReady outage with backoff and proceeds once the tracker recovers"() {
        given:
        def ledger = new SlotLedger(1)
        def calls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit ->
                if (calls.incrementAndGet() <= 2) {
                    throw new RuntimeException('tracker down')
                }
                []
            },
            listOpen : { -> [] },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        def state = automaton.step()

        then: 'two failed attempts each paused one idle-interval backoff before the third succeeded'
        state == FeedState.IDLE_EMPTY
        calls.get() == 3
        sleeper.slept.size() == 3
        sleeper.slept.every { it == IDLE }
    }

    // NFR-R3: same outage tolerance for listOpen, on the eligibility-count read inside poll().
    def "step() retries a listOpen outage with backoff and proceeds once the tracker recovers"() {
        given:
        def ledger = new SlotLedger(1)
        def calls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit -> [] },
            listOpen : {
                ->
                if (calls.incrementAndGet() <= 1) {
                    throw new RuntimeException('tracker down')
                }
                []
            },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        def state = automaton.step()

        then: 'one retry backoff, plus the ordinary Idle-empty sleep once the successful poll came back empty'
        state == FeedState.IDLE_EMPTY
        calls.get() == 2
        sleeper.slept.size() == 2
    }

    // NFR-R3: a sustained outage hitting claim() (inside claimOrAbandon, Filling path) is caught,
    //     logged, and retried with backoff rather than propagating; the eventual successful claim
    //     starts the slot exactly as if there had been no outage.
    def "step() retries a claim outage with backoff and eventually claims once the tracker recovers"() {
        given:
        def ledger = new SlotLedger(1)
        def candidate = fresh('github:o/r#1')
        def claimCalls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit -> [candidate] },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance ->
                if (claimCalls.incrementAndGet() <= 2) {
                    throw new RuntimeException('tracker down')
                }
                new ClaimResult.Acquired()
            },
        ] as Tracker
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        def automaton = automaton(tracker, ledger, capturing(claimed), new FixedRandom())

        when:
        def state = automaton.step()

        then:
        state == FeedState.FILLING
        claimCalls.get() == 3
        awaitSize(claimed, 1)
        claimed == [candidate.ref()]
        sleeper.slept.size() == 2
    }

    // NFR-R3: drain() must not confuse an outage (an exception) with a genuinely empty poll — the
    //     outage retries silently and drain only commits to stopping on the first poll that
    //     SUCCEEDS empty, after the tracker has recovered.
    def "drain retries a tracker outage and only commits to stopping on the first successful empty poll"() {
        given:
        def ledger = new SlotLedger(1)
        def calls = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit ->
                if (calls.incrementAndGet() <= 2) {
                    throw new RuntimeException('tracker down')
                }
                []
            },
            listOpen : { -> [] },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        completesWithin { automaton.drain() }

        then: 'the outage was retried, not mistaken for an empty candidate list, before stopping'
        calls.get() == 3
        sleeper.slept.size() == 2
        ledger.freeSlots() == 1
    }

    // NFR-R3: drain() also tolerates an outage on the claim path, still claiming and running the
    //     eligible task once the tracker recovers, rather than crashing the drain run.
    def "drain retries a claim outage with backoff and still claims the task on recovery"() {
        given:
        def ledger = new SlotLedger(1)
        def remaining = new CopyOnWriteArrayList<>(['1'])
        def claimAttempts = new AtomicInteger()
        Tracker tracker = [
            listReady: { int limit ->
                remaining.collect { fresh("github:o/r#${it}" as String) }
            },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance ->
                if (claimAttempts.incrementAndGet() <= 1) {
                    throw new RuntimeException('tracker down')
                }
                remaining.remove(ref.id().replace('github:o/r#', ''))
                new ClaimResult.Acquired()
            },
        ] as Tracker
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        def slotRunner = { TaskRef ref -> claimed.add(ref) } as SlotRunner
        def automaton = automaton(tracker, ledger, slotRunner, new FixedRandom())

        when:
        completesWithin { automaton.drain() }

        then:
        claimed.collect { it.id() } == ['github:o/r#1']
        sleeper.slept.size() == 1
    }

    // NFR-O1: step() must notify FeedStateLogger on every Filling transition too, not only the
    //     INFO-level Idle-blocked/Full cases already covered above — otherwise a mutant that
    //     deletes the onTransition(FILLING, ...) call would pass unnoticed since nothing else
    //     observes that call's effect.
    def "step() notifies the state logger of a Filling transition"() {
        given:
        def ledger = new SlotLedger(1)
        Tracker tracker = [
            listReady: { int limit -> [fresh('github:o/r#1')] },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        List<ILoggingEvent> events = captureLogs { automaton.step() }
        def fillingEvents = events.findAll {
            it.level == Level.DEBUG && it.formattedMessage.contains('transitioned to FILLING')
        }

        then:
        fillingEvents.size() == 1
    }

    // FR10: drain() must actually block on SlotLedger#awaitDrained() before returning — not just
    //     abandon its own permit and return once its own poll finds nothing. One slot is occupied
    //     by a task that has not yet finished when drain's own poll comes back empty; drain must
    //     stay blocked until that other slot is released, proving awaitDrained() is really awaited
    //     rather than skipped.
    def "drain blocks on awaitDrained until an unrelated still-running slot is released"() {
        given: 'one slot occupied externally, one free — drain finds nothing itself to claim'
        def ledger = new SlotLedger(2)
        def stillRunning = new TaskRef('github:o/r#still-running')
        ledger.acquire()
        ledger.assign(stillRunning)
        Tracker tracker = [
            listReady: { int limit -> [] },
            listOpen : { -> [] },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        def drainDone = new CountDownLatch(1)
        executor.submit {
            automaton.drain()
            drainDone.countDown()
        }

        expect: 'drain has not returned while the unrelated slot is still occupied'
        !drainDone.await(200, TimeUnit.MILLISECONDS)

        when: 'the still-running slot finally releases'
        ledger.release(stillRunning)

        then: 'drain proceeds past awaitDrained() and returns with every slot free again'
        drainDone.await(2, TimeUnit.SECONDS)
        ledger.freeSlots() == 2

        cleanup:
        interruptAndAwait(executor)
    }

    // NFR-O1: reaching zero free slots (the Full vantage point per class Javadoc) is logged at
    //     INFO right after the claim that filled the last slot, since the automaton has no other
    //     per-cycle decision point to observe Full from without adding a busy-poll.
    def "filling the last free slot logs Full at the moment it happens"() {
        given:
        def ledger = new SlotLedger(1)
        Tracker tracker = [
            listReady: { int limit -> [fresh('github:o/r#1')] },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker
        def automaton = automaton(tracker, ledger, capturing([]), new FixedRandom())

        when:
        List<ILoggingEvent> events = captureLogs { automaton.step() }
        def fullEvents = events.findAll { it.level == Level.INFO && it.formattedMessage.toLowerCase().contains('full') }

        then:
        fullEvents.size() == 1
    }
}
