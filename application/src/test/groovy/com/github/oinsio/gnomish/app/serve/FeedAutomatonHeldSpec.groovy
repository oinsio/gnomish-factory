package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * D19 of add-sandbox-base-image as amended 2026-10-25 (FR15, FR16): a feed a daemon condition
 * holds reads nothing from the tracker and waits the idle interval, and a drain on a held feed
 * stops as on an empty queue. Before the amendment a held step polled the tracker, abandoned its
 * permit and returned at once, so the forever loop polled at loop speed and a drain never ended.
 */
// A feed that keeps its permit while held blocks on the next acquire; the bound fails it fast.
@Timeout(10)
class FeedAutomatonHeldSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)

    private final VirtualClock clock = new VirtualClock()
    private final AtomicInteger reads = new AtomicInteger()
    private final AtomicInteger claims = new AtomicInteger()
    private final List<Duration> sleeps = []
    private final Tracker tracker = [
        listReady: { int limit ->
            budgeted()
            [
                new ReadyTask(new TaskRef('github:o/r#1'), AbortFacts.none(), false, false, UntrustedText.tracker('t'))
            ]
        },
        listOpen : {
            ->
            reads.incrementAndGet(); []
        },
        claim : { TaskRef ref, String instance ->
            claims.incrementAndGet(); null
        },
    ] as Tracker
    private final SlotLedger ledger = new SlotLedger(1, clock)

    /**
     * Counts a tracker read and fails past a budget no correct run nears: a feed that polls while
     * held spins at loop speed, and this turns the spin into a red feature instead of a hang.
     */
    private void budgeted() {
        if (reads.incrementAndGet() > 50) {
            throw new IllegalStateException('the feed spun on the tracker')
        }
    }

    private FeedAutomaton automaton(DaemonConditions conditions) {
        def sleeper = { Duration d ->
            sleeps << d; clock.advance(d)
        } as Sleeper
        new FeedAssembly(VirtualTimeEquipment.on(clock, sleeper),
                new IdleTiming(IDLE, Duration.ofMinutes(2), Duration.ofHours(1), new Random(0)), 5)
                .feedAutomaton(tracker, InstanceId.generate('gnome'), ledger, { TaskRef ref -> } as SlotRunner,
                DirtyNotifier.NOOP, conditions)
    }

    private static DaemonConditions held() {
        def gate = DaemonConditionFixtures.closedGate()
        gate.openOnFailure('origin unreachable')
        new DaemonConditions(gate, [])
    }

    // Scenario: serve/factory-serve / A held feed asks the tracker nothing
    def "a held feed reads nothing from the tracker, waits the idle interval each cycle, and reads idle-blocked"() {
        given:
        def feed = automaton(held())

        when:
        def states = (1..10).collect { feed.step() }

        then:
        reads.get() == 0
        claims.get() == 0
        sleeps.size() == 10
        sleeps.every { it >= IDLE }
        states.every { it == FeedState.IDLE_BLOCKED }
        feed.view().state() == FeedState.IDLE_BLOCKED
        ledger.freeSlots() == 1
    }

    def "a feed no condition holds polls the tracker as before"() {
        given:
        def feed = automaton(DaemonConditionFixtures.closedConditions())

        when:
        feed.step()

        then:
        reads.get() > 0
        claims.get() == 1
    }

    // Scenario: serve/factory-serve / A drain stops while the feed is held
    def "a drain on a held feed claims nothing and returns"() {
        when:
        automaton(held()).drain()

        then:
        reads.get() == 0
        claims.get() == 0
        sleeps.isEmpty()
        ledger.freeSlots() == 1
    }
}
