package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.port.contract.PortContractSupport
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * Abstract port contract for {@link Tracker} (tracker-port spec, "Port
 * contract spec suite binds every adapter"): the obligations every adapter —
 * in-memory reference, GitHub, and any third-party adapter following the
 * author guide — SHALL satisfy identically, verified through the SAME suite
 * (M1). Owns the arrangement/seeding seams ({@link #arrange}, {@link
 * #seedTask}, {@link #seedReply}) plus the {@code listReady} filtering and
 * claim-atomicity properties (tasks 2.1–2.2). The structural-marker
 * round-trip properties (task 2.3: abort facts, decision-ack round-trip) live
 * in {@link TrackerMarkerContract}, which extends this class to reuse the
 * same seams without duplicating them — kept in a second file because the
 * combined property set would exceed the project's per-file line cap.
 *
 * <p>This property covers the "Feed filtering property" scenario: {@code
 * listReady} returns only {@code Ready} tasks — never {@code Working}, {@code
 * AwaitingHuman}, {@code Finished}, or {@code Gone} — including a {@code
 * Ready} task with unexpired abort backoff, because backoff filtering is core
 * policy applied over adapter-reported facts, never the adapter's own job
 * (FR10, design D10). "Never non-task artifacts" is not exercised with a fake
 * artifact type here: an in-memory (or any conforming) adapter can only ever
 * hold {@link ReadyTask} entries by construction, so there is no non-task
 * shape for {@code listReady} to filter — the port's return type already
 * rules it out.
 *
 * <p>Implements FR4, NFR-R1 of add-tracker-port.
 */
abstract class TrackerContract extends Specification implements PortContractSupport {

    /**
     * The arrangement seam: build the {@link Tracker} adapter under test, with
     * no fixture tasks seeded yet; or return {@link Optional#empty} to declare
     * it unproducible. Seeding happens through {@link #seedTask}, since (unlike
     * a stateless sink port such as {@code AttemptPersistence}) each row needs
     * specific fixture tasks loaded before exercising the adapter.
     *
     * @return the tracker adapter under test, or empty when unproducible
     */
    protected abstract Optional<Tracker> arrange()

    /**
     * The seeding hook: loads one fixture task carrying {@code snapshot} at {@code
     * state} with {@code abortFacts} into the {@code adapter} arranged by {@link
     * #arrange}. A concrete subclass owns how its adapter's storage is populated (an
     * in-memory list, pre-loaded WireMock stubs, ...) and MUST make {@code snapshot}'s
     * title and body observable verbatim through {@code fetchTask}, so the snapshot
     * round-trip property can assert them (FR11).
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity
     * @param snapshot the fixture task's frozen id/title/body snapshot
     * @param state the fixture task's logical state
     * @param abortFacts the fixture task's abort history
     */
    protected abstract void seedTask(
    Tracker adapter, TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts)

    /**
     * Convenience overload for the rows that do not care about the snapshot's
     * title/body: seeds a default fixture snapshot whose title and body are
     * non-blank placeholders, delegating to the five-argument {@link #seedTask}.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity
     * @param state the fixture task's logical state
     * @param abortFacts the fixture task's abort history
     */
    protected void seedTask(Tracker adapter, TaskRef ref, TrackerTaskState state, AbortFacts abortFacts) {
        seedTask(adapter, ref, new TaskSnapshot(ref.id(), 'fixture title', 'fixture body'), state, abortFacts)
    }

    /**
     * The reply-seeding hook: posts one pending human reply on {@code ref} in the
     * {@code adapter} arranged by {@link #arrange}, as if a human had just replied
     * in the tracker. Callable both before and after acknowledgements against the
     * same already-arranged adapter instance, so a row can seed a reply, ack it,
     * then seed a second reply to verify the stale one never resurfaces.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity
     * @param reply the pending human reply to seed
     */
    protected abstract void seedReply(Tracker adapter, TaskRef ref, HumanReply reply)

    private static final AbortFacts UNEXPIRED_BACKOFF = new AbortFacts(2, Instant.EPOCH)

    // FR4: listReady returns only Ready tasks, in adapter queue order, never Working/AwaitingHuman/Finished/Gone
    def "listReady returns only tasks in the Ready state"() {
        given: 'a tracker seeded with one task per logical state, in queue order'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'listReady state fixture')
        def adapter = tracker.get()
        def readyA = new TaskRef('fixture:ready-a')
        def readyB = new TaskRef('fixture:ready-b')
        seedTask(adapter, readyA, new TrackerTaskState.Ready(), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:working'), new TrackerTaskState.Working('other-instance'), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:awaiting-human'),
                new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:finished'), new TrackerTaskState.Finished(), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:gone'), new TrackerTaskState.Gone(), AbortFacts.none())
        seedTask(adapter, readyB, new TrackerTaskState.Ready(), UNEXPIRED_BACKOFF)

        when: 'listReady is called'
        List<ReadyTask> result = adapter.listReady(10)

        then: 'only the Ready entries come back, in the adapter queue order they were seeded'
        result*.ref() == [readyA, readyB]
    }

    // FR4, FR10, D10: a Ready task with unexpired abort backoff is NOT filtered — that is core policy, not the adapter's
    def "listReady does not filter a Ready task by abort backoff, and carries its abort facts"() {
        given: 'a tracker seeded with one Ready task carrying an unexpired-backoff abort history'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'listReady backoff fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:ready-with-backoff')
        seedTask(adapter, ref, new TrackerTaskState.Ready(), UNEXPIRED_BACKOFF)

        when: 'listReady is called'
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the task is present, still carrying its unfiltered abort facts'
        result == [
            new ReadyTask(ref, UNEXPIRED_BACKOFF)
        ]
    }

    // FR4, NFR-R1: concurrent claim() race on one Ready task yields exactly one Acquired,
    //     every other caller gets Held naming the actual winner — repeated to rule out a
    //     race that merely appeared to pass once (M2: 100% of contract-test runs)
    def "claim is observably atomic under a concurrent race, repetition #repetition"() {
        given: 'a tracker seeded with one Ready task and N distinct caller ids'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim atomicity race fixture')
        def adapter = tracker.get()
        def ref = new TaskRef("fixture:race-${repetition}")
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())
        def callerCount = 12
        def callerIds = (1..callerCount).collect { "caller-${repetition}-${it}".toString() }
        def barrier = new CyclicBarrier(callerCount)

        when: 'every caller calls claim() concurrently, forced to line up at a shared barrier first'
        List<ClaimResult> results
        try (def pool = Executors.newVirtualThreadPerTaskExecutor()) {
            def futures = callerIds.collect { callerId ->
                pool.submit({
                    barrier.await(5, TimeUnit.SECONDS)
                    adapter.claim(ref, callerId)
                } as java.util.concurrent.Callable)
            }
            results = futures.collect { it.get(10, TimeUnit.SECONDS) }
        }

        then: 'exactly one caller acquired the claim, and every other caller was told the winner'
        def winners = results.findAll { it instanceof ClaimResult.Acquired }
        winners.size() == 1
        def winnerIndex = results.findIndexOf { it instanceof ClaimResult.Acquired }
        def winnerId = callerIds[winnerIndex]
        def losers = results.findAll { it instanceof ClaimResult.Held }
        losers.size() == callerCount - 1
        losers.every { (it as ClaimResult.Held).otherInstance() == winnerId }

        where:
        repetition << (1..5)
    }

    // FR9, per decision 0.2: claim is issued only against Ready tasks — a task already
    //     Working never yields Acquired to a new caller, only Held naming the true holder.
    //     AwaitingHuman/Finished/Gone are out of scope for this row: the take runner never
    //     attempts a claim against them (0.2 — only a human moves AwaitingHuman back to
    //     Ready), so the port leaves their claim() behavior contractually undefined here.
    def "claim against an already-Working task never yields Acquired to a new caller"() {
        given: 'a tracker seeded with one task already Working, held by another instance'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim on non-Ready fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:already-working')
        seedTask(adapter, ref, new TrackerTaskState.Working('existing-holder'), AbortFacts.none())

        when: 'a different caller attempts to claim it'
        ClaimResult result = adapter.claim(ref, 'new-caller')

        then: 'the new caller is refused, told the existing holder, never granted Acquired'
        result == new ClaimResult.Held('existing-holder')
    }
}
