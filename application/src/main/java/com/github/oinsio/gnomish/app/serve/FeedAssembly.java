package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FinishedDecline;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;

/**
 * The serve feed's assembly object: builds a {@link FeedAutomaton} from the collaborators that used
 * to be constructed inside the automaton's own constructor — {@link FeedTracker}, {@link
 * FeedOutageRetry}, {@link FeedResilience}, {@link FeedCycle} and {@link FeedViewTracker} — over the
 * timing equipment held here as fields (design D7 of add-parameter-count-gate; the
 * fields-not-parameters shape of {@code process-invariants.md}). {@link #feedAutomaton} takes only
 * the per-automaton job.
 *
 * <p>The one door into the automaton from outside this package: {@link FeedAutomaton}'s constructor
 * is package-private and takes the built {@link FeedCycle} and {@link FeedViewTracker} as values, so
 * nothing but this class can assemble one. Public because the composition root's {@code
 * ServeAssembly} lives one package up.
 *
 * <p>An assembly object in the sense of {@code testing.md}: it carries no decision, so it has no
 * spec of its own and is listed in this module's {@code pitest { excludedClasses }}; {@code
 * ServeRuntimeWiringSpec} and {@code FeedAutomatonOutageIntegrationSpec} in {@code :bootstrap} drive
 * it on the real flow.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 *
 * @param sleeper what the automaton's Idle state and outage backoff sleep on
 * @param clock the clock every poll is stamped with
 * @param idleTiming the Idle interval and jitter, and the backoff bounds and random source the
 *     feed's selection grades against
 * @param wipLimit the WIP limit W (FR6 of add-factory-serve)
 */
public record FeedAssembly(Sleeper sleeper, Clock clock, IdleTiming idleTiming, int wipLimit) {

    /**
     * Builds the feed automaton over {@code tracker}, claiming as {@code instanceId}.
     *
     * @param dirtyNotifier woken on a feed-state transition (FR1 of add-serve-observability, design
     *     D4); {@link DirtyNotifier#NOOP} absent a writer
     * @param remoteOutageGate the remote outage gate the feed's {@link FeedCycle} consults and
     *     advances every cycle (FR14, NFR-R3 of add-base-ref-resolution) — the SAME instance a
     *     slot's {@code TakeSlotRunner} opens on an {@code InfrastructureUnavailable} result
     */
    public FeedAutomaton feedAutomaton(
            Tracker tracker,
            InstanceId instanceId,
            SlotLedger slotLedger,
            SlotRunner slotRunner,
            DirtyNotifier dirtyNotifier,
            RemoteOutageGate remoteOutageGate) {
        // NFR-R3: the outage backoff reuses the Idle state's jittered interval, not a separate policy.
        // FR4: the retry's own edge logging runs on real time — the suppressor is log-plane only,
        //     never a source of behavior, so it does not join the injected-time contract the
        //     sleeper and clock above carry.
        var outageRetry = new FeedOutageRetry(sleeper, idleTiming::jittered, RepeatSuppressor.system());
        var resilience = new FeedResilience(outageRetry, new FinishedDecline(), remoteOutageGate);
        var cycle = new FeedCycle(
                new FeedTracker(tracker, instanceId),
                slotLedger,
                slotRunner,
                idleTiming.selection(wipLimit),
                new FeedStateLogger(),
                resilience);
        // FR5: a construction-time idle baseline, so a snapshot before step() reads a coherent view.
        var viewTracker = new FeedViewTracker(FeedState.IDLE_EMPTY, clock.now(), wipLimit, dirtyNotifier);
        return new FeedAutomaton(slotLedger, sleeper, clock, idleTiming, wipLimit, cycle, viewTracker);
    }
}
