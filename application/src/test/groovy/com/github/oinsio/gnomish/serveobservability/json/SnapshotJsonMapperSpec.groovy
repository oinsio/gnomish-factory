package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.KeptEnvironmentEntry
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotEntry
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link SnapshotJsonMapper} against the v1 snapshot JSON contract
 * (spec.md): status-report v1 conventions (camelCase, ISO-8601 UTC instants,
 * explicit {@code null} rather than omission), every sealed/enum branch, and
 * the {@code snapshot-v1.reference.json} byte-identity anchor.
 *
 * FR2, FR3, FR4, FR5, FR6, FR7, FR8, FR10 conventions of
 * add-serve-observability.
 */
class SnapshotJsonMapperSpec extends Specification {

    def mapper = new SnapshotJsonMapper()

    def "reference anchor: serializing the deterministic sample is byte-identical to snapshot-v1.reference.json"() {
        given:
        def referenceText = getClass().getResourceAsStream('/snapshot-v1.reference.json').getText('UTF-8')

        expect:
        mapper.serialize(referenceSnapshot()) == referenceText
    }

    def "version is always 1"() {
        expect:
        mapper.toDto(referenceSnapshot()).version() == 1
    }

    def "writtenAt and intervalSeconds render for self-describing staleness computation"() {
        given:
        def dto = mapper.toDto(referenceSnapshot())

        expect:
        dto.writtenAt() == "2026-08-02T09:00:00Z"
        dto.intervalSeconds() == 30L
    }

    def "instance section renders full instance id, host, and factory version"() {
        expect:
        mapper.toDto(referenceSnapshot()).instance() ==
                new InstanceDto("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT")
    }

    def "lifecycle running renders state with no reason"() {
        expect:
        mapper.toDto(snapshotWithLifecycle(new LifecycleState.Running())).lifecycle() ==
                new LifecycleDto("running", null)
    }

    def "lifecycle draining renders state with no reason"() {
        expect:
        mapper.toDto(snapshotWithLifecycle(new LifecycleState.Draining())).lifecycle() ==
                new LifecycleDto("draining", null)
    }

    def "lifecycle stopping renders state with no reason"() {
        expect:
        mapper.toDto(snapshotWithLifecycle(new LifecycleState.Stopping())).lifecycle() ==
                new LifecycleDto("stopping", null)
    }

    def "lifecycle stopped renders state and reason"() {
        expect:
        mapper.toDto(snapshotWithLifecycle(new LifecycleState.Stopped("signal"))).lifecycle() ==
                new LifecycleDto("stopped", "signal")
    }

    def "feed state serializes each phase to its lowerCamel wire value"() {
        expect:
        mapper.toDto(snapshotWithFeedPhase(phase)).feed().state() == wireValue

        where:
        phase | wireValue
        FeedPhase.FILLING | "filling"
        FeedPhase.IDLE_EMPTY | "idleEmpty"
        FeedPhase.IDLE_BLOCKED | "idleBlocked"
        FeedPhase.FULL | "full"
    }

    def "feed section renders since, lastPollAt, openFronts, and wipLimit"() {
        expect:
        mapper.toDto(referenceSnapshot()).feed() ==
                new FeedDto("filling", "2026-08-02T08:59:50Z", "2026-08-02T08:59:55Z", 2, 3)
    }

    def "slots section renders capacity and one entry per occupied slot"() {
        given:
        def dto = mapper.toDto(referenceSnapshot()).slots()

        expect:
        dto.capacity() == 3
        dto.entries().size() == 2
        dto.entries()[0] == new SlotEntryDto("task-42", "implement", 1, "2026-08-02T08:45:00Z")
    }

    def "a slot entry with an unreported stage renders stage as null"() {
        expect:
        mapper.toDto(referenceSnapshot()).slots().entries()[1] ==
                new SlotEntryDto("task-43", null, 0, "2026-08-02T08:50:00Z")
    }

    def "vitals heartbeat renders state, lastTickAt, and heldClaims"() {
        expect:
        mapper.toDto(referenceSnapshot()).vitals().heartbeat() ==
                new HeartbeatDto("running", "2026-08-02T08:59:58Z", 2)
    }

    def "heartbeat state serializes each value to its lowerCamel wire value"() {
        expect:
        mapper.toDto(snapshotWithHeartbeatState(state)).vitals().heartbeat().state() == wireValue

        where:
        state | wireValue
        HeartbeatState.IDLE | "idle"
        HeartbeatState.RUNNING | "running"
        HeartbeatState.DIED | "died"
    }

    def "vitals reaper renders lastRunAt, restartCount, and intervalSeconds"() {
        expect:
        mapper.toDto(referenceSnapshot()).vitals().reaper() == new ReaperDto("2026-08-02T08:55:00Z", 0, 300L)
    }

    def "vitals janitor renders lastRunAt"() {
        expect:
        mapper.toDto(referenceSnapshot()).vitals().janitor() == new JanitorDto("2026-08-02T08:00:00Z")
    }

    // NFR-O1 of add-serve-sandbox-lifecycle.
    def "vitals sweep renders the last tick's time, cadence, counts, and bounded kept inventory"() {
        given:
        def sweep = mapper.toDto(referenceSnapshot()).vitals().sweep()

        expect:
        sweep.lastTickAt() == "2026-08-02T08:58:00Z"
        sweep.intervalSeconds() == 300L
        sweep.counts() == new SweepCountsDto(4, 2, 1, 1, 3, 0)
        sweep.kept() == [
            new KeptEnvironmentDto("task-40", 172800L, 432000L),
            new KeptEnvironmentDto("task-41", 518400L, 86400L)
        ]
        sweep.keptTotal() == 2
        sweep.consecutiveSkippedTicks() == 0
    }

    // NFR-O1: a snapshot assembled before the first sweep tick renders the section as JSON null,
    //     never as a tick that counted zero.
    def "vitals sweep renders null when no tick has completed"() {
        given:
        def snapshot = referenceSnapshot()
        def vitals = snapshot.vitals()
        def preSweep = new VitalsSnapshot(vitals.heartbeat(), vitals.reaper(), vitals.janitor())

        expect:
        mapper.toDto(withVitals(snapshot, preSweep)).vitals().sweep() == null
        mapper.serialize(withVitals(snapshot, preSweep)).contains('"sweep" : null')
    }

    def "vitals has no feed or writer entries beyond heartbeat, reaper, and janitor"() {
        given:
        def json = mapper.serialize(referenceSnapshot())
        def vitalsBlock = json.substring(json.indexOf('"vitals"'), json.indexOf('"tracker"'))

        expect:
        !vitalsBlock.contains('"feed"')
        !vitalsBlock.contains('"writer"')
    }

    def "tracker renders lastSuccessAt and consecutiveFailures"() {
        expect:
        mapper.toDto(referenceSnapshot()).tracker() == new TrackerDto("2026-08-02T08:59:55Z", 0)
    }

    def "tracker lastSuccessAt renders null when the tracker has never succeeded"() {
        given:
        def snapshot = referenceSnapshot()
        def neverSucceeded = new Snapshot(
                snapshot.version(), snapshot.writtenAt(), snapshot.intervalSeconds(), snapshot.instance(),
                snapshot.lifecycle(), snapshot.feed(), snapshot.slots(), snapshot.vitals(),
                new TrackerHealth(null, 7))

        expect:
        mapper.toDto(neverSucceeded).tracker() == new TrackerDto(null, 7)
    }

    private static Snapshot snapshotWithLifecycle(LifecycleState lifecycle) {
        def snapshot = referenceSnapshot()
        return new Snapshot(
                snapshot.version(), snapshot.writtenAt(), snapshot.intervalSeconds(), snapshot.instance(),
                lifecycle, snapshot.feed(), snapshot.slots(), snapshot.vitals(), snapshot.tracker())
    }

    private static Snapshot snapshotWithFeedPhase(FeedPhase phase) {
        def snapshot = referenceSnapshot()
        def feed = snapshot.feed()
        def replacement = new FeedSnapshot(phase, feed.since(), feed.lastPollAt(), feed.openFronts(), feed.wipLimit())
        return new Snapshot(
                snapshot.version(), snapshot.writtenAt(), snapshot.intervalSeconds(), snapshot.instance(),
                snapshot.lifecycle(), replacement, snapshot.slots(), snapshot.vitals(), snapshot.tracker())
    }

    private static Snapshot snapshotWithHeartbeatState(HeartbeatState state) {
        def snapshot = referenceSnapshot()
        def vitals = snapshot.vitals()
        def heartbeat = vitals.heartbeat()
        def replacement = new HeartbeatVital(state, heartbeat.lastTickAt(), heartbeat.heldClaims())
        def replacementVitals = new VitalsSnapshot(replacement, vitals.reaper(), vitals.janitor(), vitals.sweep())
        return withVitals(snapshot, replacementVitals)
    }

    private static Snapshot withVitals(Snapshot snapshot, VitalsSnapshot vitals) {
        return new Snapshot(
                snapshot.version(), snapshot.writtenAt(), snapshot.intervalSeconds(), snapshot.instance(),
                snapshot.lifecycle(), snapshot.feed(), snapshot.slots(), vitals, snapshot.tracker())
    }

    /**
     * The deterministic sample used both by the reference-anchor spec and to
     * (re)generate {@code snapshot-v1.reference.json} — fixed {@code Instant}
     * values, two occupied slots (one with a reported stage, one without), a
     * running lifecycle, and a healthy tracker.
     */
    static Snapshot referenceSnapshot() {
        def instance = new InstanceInfo("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT")
        def feed = new FeedSnapshot(
                FeedPhase.FILLING,
                Instant.parse("2026-08-02T08:59:50Z"),
                Instant.parse("2026-08-02T08:59:55Z"),
                2,
                3)
        def slots = new SlotsSnapshot(3, [
            new SlotEntry("task-42", "implement", 1, Instant.parse("2026-08-02T08:45:00Z")),
            new SlotEntry("task-43", null, 0, Instant.parse("2026-08-02T08:50:00Z"))
        ])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.RUNNING, Instant.parse("2026-08-02T08:59:58Z"), 2),
                new ReaperVital(Instant.parse("2026-08-02T08:55:00Z"), 0, 300L),
                new JanitorVital(Instant.parse("2026-08-02T08:00:00Z")),
                new SweepVital(
                        Instant.parse("2026-08-02T08:58:00Z"),
                        300L,
                        new SweepCounts(4, 2, 1, 1, 3, 0),
                        [
                            new KeptEnvironmentEntry("task-40", 172800L, 432000L),
                            new KeptEnvironmentEntry("task-41", 518400L, 86400L)
                        ],
                        2,
                        0))
        def tracker = new TrackerHealth(Instant.parse("2026-08-02T08:59:55Z"), 0)

        return new Snapshot(
                1,
                Instant.parse("2026-08-02T09:00:00Z"),
                30L,
                instance,
                new LifecycleState.Running(),
                feed,
                slots,
                vitals,
                tracker)
    }
}
