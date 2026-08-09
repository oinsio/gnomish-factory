package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.FeedState
import com.github.oinsio.gnomish.app.serve.FeedView
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.SlotRunner
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link FeedSnapshotAssembler}: translates {@link FeedAutomaton#view()} (or a raw {@link
 * FeedView}) into the snapshot's {@code feed} section (FR5) — a name-for-name {@code FeedState}
 * -> {@code FeedPhase} mapping plus a verbatim carry of {@code since}/{@code lastPollAt}/{@code
 * openFronts}/{@code wipLimit}.
 *
 * Implements FR5 of add-serve-observability.
 */
class FeedSnapshotAssemblerSpec extends Specification {

    def "translates a raw FeedView into a FeedSnapshot field-for-field"() {
        given:
        def since = Instant.parse('2026-01-01T00:00:00Z')
        def lastPollAt = Instant.parse('2026-01-01T00:00:05Z')
        def view = new FeedView(FeedState.IDLE_BLOCKED, since, lastPollAt, 4, 3)

        when:
        def snapshot = FeedSnapshotAssembler.assemble(view)

        then:
        snapshot == new FeedSnapshot(FeedPhase.IDLE_BLOCKED, since, lastPollAt, 4, 3)
    }

    def "every FeedState value maps to the FeedPhase of the same name"() {
        expect:
        FeedState.values().every { state ->
            def view = new FeedView(state, Instant.EPOCH, Instant.EPOCH, 0, 1)
            FeedSnapshotAssembler.assemble(view).state() == FeedPhase.valueOf(state.name())
        }
    }

    def "assembling from a live FeedAutomaton reads its current view"() {
        given:
        def clock = new VirtualClock(Instant.parse('2026-02-02T00:00:00Z'))
        def sleeper = new BudgetedVirtualSleeper(clock)
        Tracker tracker = [listReady: { int limit -> [] }, listOpen: { -> [] }] as Tracker
        SlotRunner runner = { TaskRef ref -> } as SlotRunner
        def automaton = new FeedAutomaton(
                tracker, InstanceId.generate('gnome'), new SlotLedger(1), runner, sleeper, clock,
                Duration.ofMinutes(2), Duration.ofHours(1), Duration.ofSeconds(30), 2, new Random(1))

        when:
        def snapshot = FeedSnapshotAssembler.assemble(automaton)

        then: 'the construction-time idle baseline, translated'
        snapshot.state() == FeedPhase.IDLE_EMPTY
        snapshot.since() == clock.now()
        snapshot.lastPollAt() == clock.now()
        snapshot.openFronts() == 0
        snapshot.wipLimit() == 2
    }
}
