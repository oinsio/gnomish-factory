package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * TrackerShape: the recovery owner and the steadiness of every shape in the closed set — the
 * tracker medium's half of "every shape has exactly one recovery owner"
 * (docs/adr/0003-crash-consistency.md).
 *
 * FR19, FR12 of harden-task-branch-contract.
 */
class TrackerShapeSpec extends Specification {

    private static final ClaimFacts LIVE = new ClaimFacts.Live(
    'inst-1', new ClaimVersion('m-1', Instant.EPOCH, new ClaimEpoch(1)))

    def "#shape is owned by #owner"() {
        expect:
        shape.recoveryOwner() == owner

        where:
        shape || owner
        new TrackerShape.Ready() || TrackerRecoveryOwner.QUEUE
        new TrackerShape.Returned() || TrackerRecoveryOwner.QUEUE
        new TrackerShape.Claimed(LIVE) || TrackerRecoveryOwner.HOLDER
        new TrackerShape.Parked() || TrackerRecoveryOwner.HUMAN
        new TrackerShape.Finished() || TrackerRecoveryOwner.NONE
        new TrackerShape.Revoked() || TrackerRecoveryOwner.NONE
        new TrackerShape.ClaimPending() || TrackerRecoveryOwner.REAPER
        new TrackerShape.ClaimAbandoned(LIVE) || TrackerRecoveryOwner.REAPER
        new TrackerShape.IndexLagging(BoundaryKind.FINISH) || TrackerRecoveryOwner.REAPER
        new TrackerShape.Foreign('unrecognized') || TrackerRecoveryOwner.NONE
    }

    def "#shape is steady: #steady"() {
        expect:
        shape.isSteady() == steady

        where:
        shape || steady
        new TrackerShape.Ready() || true
        new TrackerShape.Returned() || true
        new TrackerShape.Claimed(LIVE) || true
        new TrackerShape.Parked() || true
        new TrackerShape.Finished() || true
        new TrackerShape.Revoked() || true
        new TrackerShape.ClaimPending() || false
        new TrackerShape.ClaimAbandoned(LIVE) || false
        new TrackerShape.IndexLagging(BoundaryKind.ABORT) || false
        new TrackerShape.Foreign('unrecognized') || false
    }

    // Exactly the three window shapes the FR12 write order can freeze are the reaper's, and no
    // other shape is: two owners for one shape would be a bug, none for a repairable one worse.
    def "the reaper owns exactly the three window shapes"() {
        given:
        def everyShape = [
            new TrackerShape.Ready(),
            new TrackerShape.Returned(),
            new TrackerShape.Claimed(LIVE),
            new TrackerShape.Parked(),
            new TrackerShape.Finished(),
            new TrackerShape.Revoked(),
            new TrackerShape.ClaimPending(),
            new TrackerShape.ClaimAbandoned(LIVE),
            new TrackerShape.IndexLagging(BoundaryKind.PARK),
            new TrackerShape.Foreign('unrecognized')
        ]

        expect:
        everyShape.findAll {
            it.recoveryOwner() == TrackerRecoveryOwner.REAPER
        }.size() == 3
        everyShape.findAll {
            !it.isSteady()
        }.findAll {
            it.recoveryOwner() == TrackerRecoveryOwner.REAPER
        }.size() == 3
    }
}
