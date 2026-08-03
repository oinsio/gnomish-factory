package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FeedCycle: the shared poll-and-claim mechanics (design D1, D2, D5) extracted from
 * FeedAutomaton. FeedAutomatonSpec exercises {@link FeedCycle#claimOrAbandon} only through
 * the happy/lost-race-but-eventually-won paths (a candidate always ends up claimed); this
 * spec drives {@link FeedCycle#claimOrAbandon} directly to cover the all-candidates-lost path,
 * where the permit reserved by the caller before the call must be returned via {@link
 * SlotLedger#abandon()} rather than left stranded.
 *
 * Also covers the own-occupied-candidate skip (FR2 of fix-reaper-idle-liveness, design D6;
 * factory-serve "Self-reaped task is not re-claimed while its old slot lives").
 *
 * Implements FR9, D5 of add-factory-serve; FR2 of fix-reaper-idle-liveness.
 */
class FeedCycleSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)

    private static ReadyTask returnedTask(String id) {
        // returned() == true so OpenFrontGate.isStillEligible short-circuits to eligible
        // without invoking the openFrontCount supplier — the scenario isolates the claim
        // race outcome, not the WIP-gate re-check (that is FeedAutomatonSpec's job).
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true, false)
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
        def fresh = new ReadyTask(new TaskRef('github:o/r#1'), AbortFacts.none(), false, false)

        when:
        cycle(tracker, ledger).claimOrAbandon([fresh])

        then: 'the WIP-blocked fresh candidate was never claimed, and the reserved permit was returned'
        claimCalls.get() == 0
        ledger.freeSlots() == 1
    }

    // FR2 of fix-reaper-idle-liveness, design D6; factory-serve "Self-reaped task is not
    //     re-claimed while its old slot lives": a claim candidate whose ref still occupies one of
    //     THIS instance's slots — the shape a self-reaped task leaves behind when the heartbeat
    //     died while its slot keeps running — is skipped with a WARN and never offered to
    //     tracker.claim (a same-instance re-claim would be invisible to the InstanceId fence and
    //     would crash on SlotLedger.assign). The walk continues: an unoccupied candidate later in
    //     the list is still claimed on this same cycle.
    def "claimOrAbandon skips a candidate still occupying a local slot and claims the next one instead"() {
        given: 'an old slot still occupies Z (its claim was self-reaped), and a fresh permit is reserved'
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(new TaskRef('github:o/r#zombie'))
        ledger.acquire()

        and: 'a tracker that records which refs are actually offered to claim'
        def claimedRefs = new CopyOnWriteArrayList<String>()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> claimedRefs.add(ref.id()); new ClaimResult.Acquired() },
        ] as Tracker

        and: 'FeedCycle log capture for the WARN line'
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(FeedCycle)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)

        when: 'the zombie ref leads the candidate list'
        try {
            cycle(tracker, ledger).claimOrAbandon([
                returnedTask('github:o/r#zombie'),
                returnedTask('github:o/r#other')
            ])
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }

        then: 'the occupied ref was never offered to claim; the next candidate was claimed instead'
        claimedRefs == ['github:o/r#other']

        and: 'the skip is loud: a WARN naming the occupied ref'
        appender.list.any { it.level == Level.WARN && it.formattedMessage.contains('github:o/r#zombie') }
    }

    // FR2, D6: with the occupied ref as the ONLY candidate, the walk finds nothing claimable and
    //     the reserved permit is returned — the feed idles instead of crashing on assign() or
    //     stranding the permit.
    def "claimOrAbandon abandons the reserved permit when the only candidate still occupies a local slot"() {
        given:
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(new TaskRef('github:o/r#zombie'))
        ledger.acquire()
        def claimCalls = new AtomicInteger()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> claimCalls.incrementAndGet(); new ClaimResult.Acquired() },
        ] as Tracker

        when:
        cycle(tracker, ledger).claimOrAbandon([
            returnedTask('github:o/r#zombie')
        ])

        then: 'no claim was attempted and the fresh permit went back to the pool (1 of 2 free: the zombie keeps its slot)'
        claimCalls.get() == 0
        ledger.freeSlots() == 1
    }

    // FR2, D6: the skip is occupancy-scoped, not a permanent blacklist — once the old slot
    //     releases the ref (the zombie aborted at its round boundary), the very next cycle may
    //     claim the same ref again as an ordinary fresh claim.
    def "the same ref becomes claimable again once its old slot releases"() {
        given: 'the zombie slot has released; only the ledger history remembers it'
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(new TaskRef('github:o/r#zombie'))
        ledger.release(new TaskRef('github:o/r#zombie'))
        ledger.acquire()
        def claimedRefs = new CopyOnWriteArrayList<String>()
        Tracker tracker = [
            claim: { TaskRef ref, String instance -> claimedRefs.add(ref.id()); new ClaimResult.Acquired() },
        ] as Tracker

        when:
        cycle(tracker, ledger).claimOrAbandon([
            returnedTask('github:o/r#zombie')
        ])

        then: 'the ref was claimed, assigned, and its slot ran to completion — an ordinary fresh claim'
        claimedRefs == ['github:o/r#zombie']
        // The instantly-finishing slot body may have released already; bounded drain proves the
        // assign-run-release cycle completed rather than racing occupiedRefs() directly.
        ledger.awaitDrained(Duration.ofSeconds(5))
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
        def started = new CopyOnWriteArrayList<TaskRef>()
        def runnerStarted = new CountDownLatch(1)
        def releaseRunner = new CountDownLatch(1)
        // The runner blocks until the test says so, so the slot's finally-release() cannot
        // race ahead of the freeSlots() assertion below.
        SlotRunner runner = { TaskRef ref ->
            started.add(ref)
            runnerStarted.countDown()
            releaseRunner.await(2, TimeUnit.SECONDS)
        } as SlotRunner

        when:
        cycle(tracker, ledger, runner).claimOrAbandon([returnedTask('github:o/r#1')])
        runnerStarted.await(2, TimeUnit.SECONDS)

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
