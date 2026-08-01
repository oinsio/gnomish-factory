package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FeedCycle: the shared poll-and-claim mechanics (design D1, D2, D5) extracted from
 * FeedAutomaton. FeedAutomatonSpec exercises {@link FeedCycle#claimOrAbandon} only through
 * the happy/lost-race-but-eventually-won paths (a candidate always ends up claimed); this
 * spec drives {@link FeedCycle#claimOrAbandon} directly to cover the all-candidates-lost path,
 * where the permit reserved by the caller before the call must be returned via {@link
 * SlotLedger#abandon()} rather than left stranded.
 *
 * Implements FR9, D5 of add-factory-serve.
 */
class FeedCycleSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)

    private static ReadyTask returnedTask(String id) {
        // returned() == true so OpenFrontGate.isStillEligible short-circuits to eligible
        // without invoking the openFrontCount supplier — the scenario isolates the claim
        // race outcome, not the WIP-gate re-check (that is FeedAutomatonSpec's job).
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true)
    }

    private static FeedCycle cycle(Tracker tracker, SlotLedger ledger, SlotRunner runner = { TaskRef ref -> } as SlotRunner) {
        // Budgeted: a mutant that breaks claimOrAbandon outright (e.g. assign(null) -> NPE) spins
        // FeedOutageRetry's retry-forever loop; the budget fails the spec instead of hanging it.
        def sleeper = new BudgetedVirtualSleeper(new VirtualClock())
        def outageRetry = new FeedOutageRetry(sleeper, { Duration.ofSeconds(1) })
        new FeedCycle(tracker, INSTANCE, ledger, runner, BASE, CAP, 2, new Random(0), new FeedStateLogger(), outageRetry)
    }

    // FR9, D5: every candidate loses the claim race (Held) — attemptClaim falls through the
    //     whole list and returns null, so claimOrAbandon must return the permit the caller
    //     already reserved via SlotLedger#acquire() before calling in, rather than stranding
    //     it. Verified on the real SlotLedger's observable state (permit count), which is
    //     exactly what SlotLedger#abandon() changes — a removed call to it leaves freeSlots()
    //     at 0 instead of restoring it to 1.
    def "claimOrAbandon abandons the reserved permit when every candidate loses the claim race"() {
        given: 'a ledger with its one permit already reserved, as the real caller (FeedAutomaton) does before claimOrAbandon'
        def ledger = new SlotLedger(1)
        ledger.acquire()

        and: 'a tracker that reports every candidate as already held by another instance'
        def claimCalls = new AtomicInteger()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> claimCalls.incrementAndGet(); new ClaimResult.Held('other-instance') },
        ] as Tracker

        when:
        cycle(tracker, ledger).claimOrAbandon([
            returnedTask('github:o/r#1'),
            returnedTask('github:o/r#2')
        ])

        then: 'both candidates were raced away and the reserved permit was returned to the pool, not stranded'
        claimCalls.get() == 2
        ledger.freeSlots() == 1
    }

    // FR9, D5: an empty candidate list is another way attemptClaim returns null without ever
    //     calling tracker.claim — must still abandon the reserved permit.
    def "claimOrAbandon abandons the reserved permit when the candidate list is empty"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker

        when:
        cycle(tracker, ledger).claimOrAbandon([])

        then:
        ledger.freeSlots() == 1
    }

    // FR6, D5, M4: a FRESH (non-returned) candidate's per-claim WIP re-check
    // (OpenFrontGate.isStillEligible) must actually consult the live tracker.listOpen() count
    // — this is the one path FeedCycleSpec's `returnedTask` helper deliberately never exercises
    // (see its own comment above). With wipLimit=2 and a live open-front count of 2, the
    // candidate is ineligible and must never reach tracker.claim: a mutant that hard-codes the
    // supplied count to 0 would wrongly consider it eligible (0 < 2) and claim it.
    def "claimOrAbandon skips a fresh candidate whose live open-front count has reached the WIP limit"() {
        given: 'a ledger with its one permit already reserved, and a tracker at the WIP limit (2 open)'
        def ledger = new SlotLedger(1)
        ledger.acquire()
        def claimCalls = new AtomicInteger()
        // Only the list's size is ever read (OpenFrontGate.isStillEligible), so a plain
        // two-element placeholder list stands in for two open OpenTask records — OpenTask is a
        // record and this project has no bytecode-manipulation agent configured for Spock to
        // stub one (see ServeShutdownWiringSpec's equivalent note on final production classes).
        Tracker tracker = [
            listOpen: { -> [new Object(), new Object()] },
            claim   : { TaskRef ref, String instance -> claimCalls.incrementAndGet(); new ClaimResult.Acquired() },
        ] as Tracker
        def fresh = new ReadyTask(new TaskRef('github:o/r#1'), AbortFacts.none(), false)

        when:
        cycle(tracker, ledger).claimOrAbandon([fresh])

        then: 'the WIP-blocked fresh candidate was never claimed, and the reserved permit was returned'
        claimCalls.get() == 0
        ledger.freeSlots() == 1
    }

    // FR9, D5: the mirror success path — a candidate wins the claim race, so the reserved
    //     permit is assigned to it (not abandoned) and the slot body starts. Keeps the
    //     abandon-path assertions above honest by proving freeSlots() only returns to 1 via
    //     release() once the started slot finishes, not via a stray abandon() on the success
    //     branch.
    def "claimOrAbandon assigns the reserved permit and starts the slot when a candidate wins the claim race"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker
        def started = new java.util.concurrent.CopyOnWriteArrayList<TaskRef>()
        def runnerStarted = new java.util.concurrent.CountDownLatch(1)
        def releaseRunner = new java.util.concurrent.CountDownLatch(1)
        // The runner blocks until the test says so, so the slot's finally-release() cannot
        // race ahead of the freeSlots() assertion below.
        SlotRunner runner = { TaskRef ref ->
            started.add(ref)
            runnerStarted.countDown()
            releaseRunner.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } as SlotRunner

        when:
        cycle(tracker, ledger, runner).claimOrAbandon([returnedTask('github:o/r#1')])
        runnerStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)

        then: 'the permit stays occupied by the claimed task, not returned to the pool'
        started.collect { it.id() } == ['github:o/r#1']
        ledger.freeSlots() == 0

        when: 'the slot body is allowed to finish'
        releaseRunner.countDown()

        // Bounded on purpose: a mutant that strands the permit (a dropped assign() making the
        // finally-release() throw, or a dropped release() itself) fails here as a red assertion
        // instead of hanging the spec in an unbounded awaitDrained().
        then: 'the finishing slot returns its permit within the bound, not stranding it'
        ledger.awaitDrained(Duration.ofSeconds(5))
    }
}
