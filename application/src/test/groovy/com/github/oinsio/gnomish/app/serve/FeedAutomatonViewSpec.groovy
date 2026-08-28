package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * {@link FeedAutomaton#view()}: the automaton's own read model for the snapshot's {@code feed}
 * section (design D3) — current {@link FeedState}, when it entered that state, the last poll
 * instant, open fronts, and the configured WIP limit — tracked at the same call sites {@link
 * FeedStateLogger} already logs from, so a caller outside {@code app.serve} (the observability
 * assembler, task 2.3) never has to re-derive it.
 *
 * Implements FR5 of add-serve-observability.
 */
class FeedAutomatonViewSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final int WIP_LIMIT = 2

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

    private final VirtualClock clock = new VirtualClock(Instant.parse('2026-01-01T00:00:00Z'))
    private final def sleeper = new BudgetedVirtualSleeper(clock)

    private static ReadyTask fresh(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false, false, 'fixture title')
    }

    private static SlotRunner noop() {
        { TaskRef ref -> } as SlotRunner
    }

    private FeedAutomaton automaton(Tracker tracker, SlotLedger ledger, int wipLimit = WIP_LIMIT) {
        new FeedAutomaton(tracker, INSTANCE, ledger, noop(), sleeper, clock, BASE, CAP, IDLE, wipLimit, new FixedRandom())
    }

    def "before any cycle runs, the view reports an idle state at construction time"() {
        given:
        Tracker tracker = [listReady: { int limit ->
                []
            }, listOpen: {
                -> []
            }] as Tracker
        def a = automaton(tracker, new SlotLedger(1))

        expect:
        def view = a.view()
        view.state() == FeedState.IDLE_EMPTY
        view.since() == clock.now()
        view.lastPollAt() == clock.now()
        view.openFronts() == 0
        view.wipLimit() == WIP_LIMIT
    }

    def "a Filling cycle reports FILLING with the poll's open-front count and the poll instant as lastPollAt"() {
        given: 'open fronts below the WIP limit, so the fresh candidate is claim-eligible'
        def openFronts = [
            new OpenTask(new TaskRef('github:o/r#open-1'), new TrackerTaskState.Working('other'), null, 'fixture title')
        ]
        Tracker tracker = [
            listReady: { int limit -> [fresh('github:o/r#1')] },
            listOpen : { -> openFronts },
            claim : { TaskRef ref, String instance ->
                new ClaimResult.Acquired(new ClaimEpoch(1))
            },
        ] as Tracker
        def a = automaton(tracker, new SlotLedger(1))
        clock.advance(Duration.ofSeconds(5))

        when:
        a.step()

        then:
        def view = a.view()
        view.state() == FeedState.FILLING
        view.since() == clock.now()
        view.lastPollAt() == clock.now()
        view.openFronts() == 1
        view.wipLimit() == WIP_LIMIT
    }

    def "since is only updated on an actual state change, not on every cycle in the same state"() {
        given:
        Tracker tracker = [listReady: { int limit ->
                []
            }, listOpen: {
                -> []
            }] as Tracker
        def a = automaton(tracker, new SlotLedger(1))
        def firstSince = a.view().since()
        clock.advance(Duration.ofSeconds(10))
        def expectedPollAt = clock.now()

        when: 'a second cycle lands in the same Idle-empty state'
        a.step()

        then: 'since is unchanged even though lastPollAt moved forward'
        def view = a.view()
        view.state() == FeedState.IDLE_EMPTY
        view.since() == firstSince
        view.lastPollAt() == expectedPollAt
    }

    def "a transition from Filling to Idle-empty updates since to the transition instant"() {
        given:
        def fillingCounter = 0
        Tracker tracker = [
            listReady: { int limit ->
                fillingCounter == 0 ? [fresh('github:o/r#1')] : []
            },
            listOpen : { -> [] },
            claim : { TaskRef ref, String instance ->
                fillingCounter++; new ClaimResult.Acquired(new ClaimEpoch(1))
            },
        ] as Tracker
        def a = automaton(tracker, new SlotLedger(2))
        a.step()
        assert a.view().state() == FeedState.FILLING

        when:
        clock.advance(Duration.ofSeconds(7))
        def expectedTransitionAt = clock.now()
        a.step()

        then:
        def view = a.view()
        view.state() == FeedState.IDLE_EMPTY
        view.since() == expectedTransitionAt
    }

    def "the view reports FULL while the automaton is blocked waiting for a free slot"() {
        given: 'a single-slot ledger already fully occupied before the automaton ever runs'
        def busy = new TaskRef('github:o/r#busy')
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(busy)
        Tracker tracker = [listReady: { int limit ->
                []
            }, listOpen: {
                -> []
            }] as Tracker
        def a = automaton(tracker, ledger)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()
        def stepDone = new CountDownLatch(1)
        executor.submit {
            a.step()
            stepDone.countDown()
        }

        expect: 'the view already reports FULL while step() is parked in acquire()'
        new PollingConditions(timeout: 2).eventually {
            assert a.view().state() == FeedState.FULL
        }
        !stepDone.await(200, TimeUnit.MILLISECONDS)

        when: 'the occupying slot releases'
        ledger.release(busy)

        then: 'the parked step() proceeds and the view moves on to the observed cycle state'
        stepDone.await(2, TimeUnit.SECONDS)
        a.view().state() == FeedState.IDLE_EMPTY

        cleanup:
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
