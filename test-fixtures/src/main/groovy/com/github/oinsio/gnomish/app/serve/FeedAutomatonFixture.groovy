package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Duration

/**
 * The two defaulting {@link FeedAutomaton} constructors, moved out of {@code src/main} (task 4.2,
 * D5 of collapse-composition-roots): no production code called them, and the gate default they
 * supplied was a real-clock {@code RemoteOutageGates.system(...)} the test-time gate could not
 * see there. Here the default gate runs on virtual time — it starts closed and nothing reachable
 * from these factories opens it, so its clock is never consulted (a closed gate's {@code
 * probeIfDue()} is a no-op).
 *
 * <p>Shared by the {@code :application} and {@code :bootstrap} specs, which is why it lives in
 * {@code :test-fixtures} rather than one module's test tree.
 */
class FeedAutomatonFixture {

    /** The feed automaton with no observability writer and a closed remote outage gate. */
    static FeedAutomaton feedAutomaton(
            Tracker tracker, InstanceId instanceId, SlotLedger slotLedger, SlotRunner slotRunner, Sleeper sleeper,
            Clock clock, Duration backoffBase, Duration backoffCap, Duration idlePollInterval, int wipLimit,
            Random random) {
        feedAutomaton(tracker, instanceId, slotLedger, slotRunner, sleeper, clock, backoffBase, backoffCap,
                idlePollInterval, wipLimit, random, DirtyNotifier.NOOP)
    }

    /** As above plus a {@link DirtyNotifier} (FR1 of add-serve-observability, design D4). */
    static FeedAutomaton feedAutomaton(
            Tracker tracker, InstanceId instanceId, SlotLedger slotLedger, SlotRunner slotRunner, Sleeper sleeper,
            Clock clock, Duration backoffBase, Duration backoffCap, Duration idlePollInterval, int wipLimit,
            Random random, DirtyNotifier dirtyNotifier) {
        new FeedAutomaton(tracker, instanceId, slotLedger, slotRunner, sleeper, clock,
                new IdleTiming(idlePollInterval, backoffBase, backoffCap, random), wipLimit, dirtyNotifier,
                closedGate(idlePollInterval))
    }

    private static RemoteOutageGate closedGate(Duration idlePollInterval) {
        new RemoteOutageGate(BaseRefGit.UNWIRED, Path.of('.'), new VirtualClock(), new Random(0), idlePollInterval,
                RemoteOutageGates.DEFAULT_CAP)
    }
}
