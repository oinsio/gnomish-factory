package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState
import com.github.oinsio.gnomish.app.serve.DaemonLifecycleView
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * {@link LifecycleSnapshotAssembler}: maps {@code app.serve}'s {@link DaemonLifecycleState} onto
 * this package's sealed {@link LifecycleState} (FR4), carrying the reason across only for the
 * STOPPED state.
 *
 * Implements FR4 of add-serve-observability.
 */
class LifecycleSnapshotAssemblerSpec extends Specification {

    private static final Instant T0 = Instant.parse('2026-08-03T10:00:00Z')

    @Unroll
    def "maps #daemonState to the matching non-terminal LifecycleState"() {
        given:
        def view = new DaemonLifecycleView(daemonState, T0, null)

        expect:
        LifecycleSnapshotAssembler.assemble(view) == expected

        where:
        daemonState | expected
        DaemonLifecycleState.RUNNING | new LifecycleState.Running()
        DaemonLifecycleState.DRAINING | new LifecycleState.Draining()
        DaemonLifecycleState.STOPPING | new LifecycleState.Stopping()
    }

    def "maps STOPPED with its reason"() {
        given:
        def view = new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0, 'sigterm')

        expect:
        LifecycleSnapshotAssembler.assemble(view) == new LifecycleState.Stopped('sigterm')
    }

    def "STOPPED with no reason is an invalid view and fails loudly rather than silently dropping it"() {
        given:
        def view = new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0, null)

        when:
        LifecycleSnapshotAssembler.assemble(view)

        then:
        thrown(IllegalStateException)
    }

    def "assembling from a tracker delegates to its current view"() {
        given:
        def tracker = new LifecycleStateTracker(T0)
        tracker.stop('drainComplete', T0.plusSeconds(3))

        expect:
        LifecycleSnapshotAssembler.assemble(tracker) == new LifecycleState.Stopped('drainComplete')
    }
}
