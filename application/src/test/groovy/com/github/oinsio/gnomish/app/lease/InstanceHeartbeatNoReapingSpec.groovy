package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The beat thread's one duty (FR1, FR4 of add-claim-heartbeat; FR5 of fix-reaper-idle-liveness):
 * it beats the instance's own held claims and nothing else. The sweep and every repair belong to
 * the standing reaper, on its own thread, so a dead or stalled beat never stops reaping and a
 * reaping fault never stops the beat. {@link InstanceHeartbeat} is the beat of both {@code take}
 * and {@code serve}; its tick is driven directly under a parked sleeper, and every tracker
 * interaction it makes is accounted for.
 */
class InstanceHeartbeatNoReapingSpec extends Specification {

    private static final TaskRef OWN = new TaskRef('github:o/r#1')
    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))

    private final Tracker tracker = Mock()
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), VirtualTimeEquipment.on(new VirtualClock(), new BlockingSleeper()),
    Duration.ofMinutes(5), { TaskRef ref -> } as ClaimLostSink)

    def cleanup() {
        heartbeat.unregister(OWN)
    }

    // FR4 of add-claim-heartbeat: the reaper is not gated on, nor run by, the beat
    // Scenario: lifecycle/claim-heartbeat / The heartbeat thread runs no reaping
    def "FR4: a beat writes its own held claim and makes no listing, sweep or repair call"() {
        given:
        heartbeat.register(OWN)

        when:
        heartbeat.tick()
        heartbeat.tick()

        then: 'one beat per tick on the held claim'
        2 * tracker.heartbeat(OWN, _) >> BEATEN

        and: 'nothing else: no listing, no stale-claim removal, no index repair'
        0 * tracker._
    }
}
