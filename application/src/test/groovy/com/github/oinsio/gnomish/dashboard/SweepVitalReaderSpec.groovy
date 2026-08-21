package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures
import java.time.Instant
import spock.lang.Specification

/**
 * {@link SweepVitalReader}, task 6.3 of add-serve-sandbox-lifecycle (NFR-O3): every
 * snapshot-carrying view yields its sweep vital — including the cleanly stopped one, whose kept
 * environments are exactly what an operator returning to a stopped instance needs to see — while a
 * snapshot from a build without the vital reads as absent, never as a tick that counted zero.
 */
class SweepVitalReaderSpec extends Specification {

    static final SweepVital SWEEP = new SweepVital(
    Instant.parse('2026-08-06T09:00:00Z'), 300L, SweepCounts.NONE, [], 0, 0)

    private static Snapshot withSweep() {
        def base = DaemonSnapshotFixtures.snapshot(new LifecycleState.Running())
        def vitals = base.vitals()
        return new Snapshot(
                base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(), base.lifecycle(),
                base.feed(), base.slots(),
                new VitalsSnapshot(vitals.heartbeat(), vitals.reaper(), vitals.janitor(), SWEEP),
                base.tracker())
    }

    def "every snapshot-carrying view yields its sweep vital"() {
        expect:
        SweepVitalReader.read(view) == SWEEP

        where:
        view << [
            new DaemonSnapshotView.Fresh(withSweep()),
            new DaemonSnapshotView.DeadDaemon(withSweep()),
            new DaemonSnapshotView.StoppedStale(withSweep())
        ]
    }

    // NFR-O3: no snapshot at all, and a snapshot from a build before this contract, both read as
    //     "no sweep data" — the section's honest degraded state.
    def "an absent snapshot or an absent vital reads as no sweep data"() {
        expect:
        SweepVitalReader.read(view) == null

        where:
        view << [
            new DaemonSnapshotView.Absent(),
            new DaemonSnapshotView.Fresh(DaemonSnapshotFixtures.snapshot(new LifecycleState.Running()))
        ]
    }
}
