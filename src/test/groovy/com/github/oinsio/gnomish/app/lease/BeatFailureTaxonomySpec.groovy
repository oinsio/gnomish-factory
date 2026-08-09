package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Beat failures are classified, not counted (design D7, FR8): the two taxonomy branches driven
 * against the real {@link InstanceHeartbeat} with a real {@link ClaimLossFlag} as the sink. A
 * transient infrastructure outage (network/5xx) is logged WARN and the run continues, beats
 * resuming when the tracker recovers; a "claim marker gone" answer flags the loss so the take
 * run stops at its nearest round boundary exactly like a revocation. These specs drive tick()
 * directly under a parked sleeper, so the taxonomy is exercised with no threading and no real
 * time; the full boundary stop+salvage+push reaction is take integration (task 6.1/6.3).
 *
 * FR8 of add-claim-heartbeat.
 */
class BeatFailureTaxonomySpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH))

    private final Tracker tracker = Mock()
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final VirtualClock clock = new VirtualClock()
    private final ClaimLossFlag flag = new ClaimLossFlag()
    // A parked sleeper: the auto-started worker blocks in its first sleep, so the only ticks
    // are the direct hb.tick() calls below — no background beating races these assertions.
    private final InstanceHeartbeat hb = new InstanceHeartbeat(
    tracker,
    progress,
    new BlockingSleeper(),
    clock,
    INTERVAL,
    flag)

    private void progressAt(TaskRef ref, String stage, int attempt) {
        ProgressFixtures.progressAt(progress, ref, stage, attempt)
    }

    def cleanup() {
        // Drain the held set so the register()-started beat worker terminates on its next pass. This
        // bounds a sleep-dropping mutant's busy-spin (the worker checks the held set each cycle)
        // rather than leaking a spinning thread into PIT's reused minion — see InstanceHeartbeatSpec.
        hb.unregister(A)
    }

    // FR8: transient outage does not stop work — three consecutive 5xx beats are each caught,
    //     the claim stays held and flagged nothing, and beats resume once the tracker recovers.
    def "a transient outage does not stop work and beats resume on recovery"() {
        given:
        progressAt(A, 'plan', 0)
        hb.register(A)

        when: 'three consecutive beats fail with a 5xx-style exception'
        hb.tick()
        hb.tick()
        hb.tick()

        then: 'each failure is swallowed — the tick never propagates it and the claim is not lost'
        3 * tracker.heartbeat(A, _) >> { throw new RuntimeException('5xx') }
        noExceptionThrown()
        !flag.isLost(A)

        when: 'the tracker recovers'
        hb.tick()

        then: 'the same claim beats again — it was never dropped'
        1 * tracker.heartbeat(A, _) >> BEATEN
        !flag.isLost(A)
    }

    // FR8: a lost claim ends the run at the boundary — a ClaimGone beat sets the claim-loss
    //     flag (so a round-boundary check reacts) and the dead claim is dropped from the tick.
    def "a lost claim sets the claim-loss flag and drops the claim"() {
        given:
        progressAt(A, 'plan', 0)
        hb.register(A)

        when:
        hb.tick()

        then: 'the marker-gone answer flags the loss for the next round boundary'
        1 * tracker.heartbeat(A, _) >> new HeartbeatResult.ClaimGone()
        flag.isLost(A)

        when: 'the next tick — the lost claim is no longer beaten'
        hb.tick()

        then:
        0 * tracker.heartbeat(A, _)
        flag.isLost(A)
    }
}
