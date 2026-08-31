package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link FeedAutomaton}'s end-to-end {@link DirtyNotifier} wiring (FR1 of add-serve-observability,
 * design D4): a feed-state transition observed via {@link FeedAutomaton#step()} wakes the injected
 * notifier without waiting for the snapshot writer's own timer beat; a poll that lands back in the
 * same state does not.
 *
 * Implements FR1 of add-serve-observability.
 */
class FeedAutomatonDirtyNotifierSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final int WIP_LIMIT = 2

    private final VirtualClock clock = new VirtualClock(Instant.parse('2026-01-01T00:00:00Z'))
    private final def sleeper = new BudgetedVirtualSleeper(clock)

    private static SlotRunner noop() {
        { TaskRef ref -> } as SlotRunner
    }

    def "a Filling transition wakes the injected dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        Tracker tracker = [
            listReady: { int limit ->
                [
                    new ReadyTask(new TaskRef('github:o/r#1'),
                    AbortFacts.none(), false, false, 'fixture title')
                ]
            },
            listOpen : { -> [] },
            claim : { TaskRef ref, String instance ->
                new ClaimResult.Acquired(new ClaimEpoch(1))
            },
        ] as Tracker
        def automaton = new FeedAutomaton(tracker, INSTANCE, new SlotLedger(1), noop(), sleeper, clock,
                BASE, CAP, IDLE, WIP_LIMIT, new Random(1), notifier)

        when:
        automaton.step()

        then:
        1 * notifier.markDirty()
    }

    def "a same-state Idle-empty poll does not wake the injected dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        Tracker tracker = [listReady: { int limit ->
                []
            }, listOpen: {
                -> []
            }] as Tracker
        def automaton = new FeedAutomaton(tracker, INSTANCE, new SlotLedger(1), noop(), sleeper, clock,
                BASE, CAP, IDLE, WIP_LIMIT, new Random(1), notifier)

        when: 'a second cycle lands back in the same Idle-empty state as construction'
        automaton.step()

        then:
        0 * notifier.markDirty()
    }

    def "the eleven-arg constructor defaults to a no-op notifier"() {
        given:
        Tracker tracker = [listReady: { int limit ->
                []
            }, listOpen: {
                -> []
            }] as Tracker
        def automaton = new FeedAutomaton(tracker, INSTANCE, new SlotLedger(1), noop(), sleeper, clock,
                BASE, CAP, IDLE, WIP_LIMIT, new Random(1))

        expect:
        automaton.view().state() == FeedState.IDLE_EMPTY
    }
}
