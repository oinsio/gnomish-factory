package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.RemoteHealth
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapperSpec
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies {@link AlertConditionEvaluator} flags operator-guide rules 1–5
 * (design D9 of add-serve-observability) over a single {@link
 * DaemonSnapshotView} — rule 6 needs check-to-check history and is out of
 * scope (design D3).
 *
 * FR4 of add-dashboard-page.
 */
class AlertConditionEvaluatorSpec extends Specification {

    private static final Instant WRITTEN_AT = Instant.parse('2026-08-02T09:00:00Z')
    // intervalSeconds = 30, k = 3 -> staleness threshold is 90s (matches SnapshotReaderSpec).
    private static final Instant FRESH_NOW = WRITTEN_AT.plusSeconds(60)
    private static final Instant STALE_NOW = WRITTEN_AT.plusSeconds(91)

    @Unroll
    def "rule #rule fires exactly when its condition holds"() {
        given:
        def view = new DaemonSnapshotView.Fresh(snapshot)

        expect:
        AlertConditionEvaluator.evaluate(view, FRESH_NOW).contains(expected) == shouldFire

        where:
        rule | snapshot | expected | shouldFire
        'rule2 (occupied slots, dead heartbeat)' | snapshotWithHeartbeat(HeartbeatState.DIED) | new AlertCondition.OccupiedSlotsHeartbeatNotRunning() | true
        'rule2 (occupied slots, running heartbeat)' | snapshotWithHeartbeat(HeartbeatState.RUNNING) | new AlertCondition.OccupiedSlotsHeartbeatNotRunning() | false
        'rule3 (long idleBlocked)' | snapshotWithIdleBlockedSince(FRESH_NOW.minusSeconds(31 * 60)) | new AlertCondition.LongIdleBlocked() | true
        'rule3 (fresh idleBlocked)' | snapshotWithIdleBlockedSince(FRESH_NOW.minusSeconds(60)) | new AlertCondition.LongIdleBlocked() | false
        'rule4 (consecutiveFailures present)' | snapshotWithConsecutiveFailures(3) | new AlertCondition.TrackerFailuresPresent() | true
        'rule4 (no consecutiveFailures)' | snapshotWithConsecutiveFailures(0) | new AlertCondition.TrackerFailuresPresent() | false
        'rule5 (stale reaper lastRunAt)' | snapshotWithReaper(WRITTEN_AT.minusSeconds(1000), 0) | new AlertCondition.ReaperDegraded() | true
        'rule5 (reaper restartCount > 0)' | snapshotWithReaper(FRESH_NOW.minusSeconds(10), 2) | new AlertCondition.ReaperDegraded() | true
        'rule5 (fresh reaper, no restarts)' | snapshotWithReaper(FRESH_NOW.minusSeconds(10), 0) | new AlertCondition.ReaperDegraded() | false
    }

    def "rule1 fires for DeadDaemon and not for Fresh or StoppedStale"() {
        given:
        def snapshot = SnapshotJsonMapperSpec.referenceSnapshot()

        expect:
        AlertConditionEvaluator.evaluate(new DaemonSnapshotView.DeadDaemon(snapshot), STALE_NOW)
                .contains(new AlertCondition.StaleWhileNotStopped())
        !AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(snapshot), FRESH_NOW)
                .contains(new AlertCondition.StaleWhileNotStopped())
        AlertConditionEvaluator.evaluate(new DaemonSnapshotView.StoppedStale(snapshot), STALE_NOW).isEmpty()
    }

    def "Absent view flags nothing"() {
        expect:
        AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Absent(), FRESH_NOW).isEmpty()
    }

    def "rule3 does not fire exactly at the 30-minute idleBlocked threshold, only beyond it"() {
        given:
        def atThreshold = new DaemonSnapshotView.Fresh(snapshotWithIdleBlockedSince(FRESH_NOW.minusSeconds(30 * 60)))
        def beyondThreshold = new DaemonSnapshotView.Fresh(snapshotWithIdleBlockedSince(FRESH_NOW.minusSeconds(30 * 60 + 1)))

        expect:
        !AlertConditionEvaluator.evaluate(atThreshold, FRESH_NOW).contains(new AlertCondition.LongIdleBlocked())
        AlertConditionEvaluator.evaluate(beyondThreshold, FRESH_NOW).contains(new AlertCondition.LongIdleBlocked())
    }

    def "rule5 reaper staleness threshold is intervalSeconds x k (multiplication), not intervalSeconds / k"() {
        given: 'intervalSeconds=300, k=3 -> correct threshold 900s; a division mutant would give 100s'
        def lastRunAt = FRESH_NOW.minusSeconds(500)
        def view = new DaemonSnapshotView.Fresh(snapshotWithReaper(lastRunAt, 0))

        expect: '500s age is beyond a 100s (division) threshold but within the correct 900s one'
        !AlertConditionEvaluator.evaluate(view, FRESH_NOW).contains(new AlertCondition.ReaperDegraded())
    }

    def "rule5 reaper staleness does not fire exactly at the threshold, only beyond it"() {
        given:
        def atThreshold = new DaemonSnapshotView.Fresh(snapshotWithReaper(FRESH_NOW.minusSeconds(900), 0))
        def beyondThreshold = new DaemonSnapshotView.Fresh(snapshotWithReaper(FRESH_NOW.minusSeconds(901), 0))

        expect:
        !AlertConditionEvaluator.evaluate(atThreshold, FRESH_NOW).contains(new AlertCondition.ReaperDegraded())
        AlertConditionEvaluator.evaluate(beyondThreshold, FRESH_NOW).contains(new AlertCondition.ReaperDegraded())
    }

    private static Snapshot snapshotWithHeartbeat(HeartbeatState state) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def heartbeat = new HeartbeatVital(state, base.vitals().heartbeat().lastTickAt(), base.vitals().heartbeat().heldClaims())
        def vitals = new VitalsSnapshot(heartbeat, base.vitals().reaper(), base.vitals().janitor())
        return withVitals(base, vitals)
    }

    private static Snapshot snapshotWithIdleBlockedSince(Instant since) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def feed = new FeedSnapshot(FeedPhase.IDLE_BLOCKED, since, base.feed().lastPollAt(), base.feed().openFronts(), base.feed().wipLimit())
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), feed, base.slots(), base.vitals(), base.tracker(), [:])
    }

    // NFR-O3, UX6 of add-base-ref-resolution.
    def "an open remote gate is flagged, a closed one is quiet, and an absent section raises nothing"() {
        given:
        def open = snapshotWithRemote([origin: new RemoteHealth(
                    'origin', true, FRESH_NOW.minusSeconds(30), 'connection refused', FRESH_NOW.plusSeconds(60), 3, null)])
        def closed = snapshotWithRemote([origin: new RemoteHealth('origin', false, null, null, null, 0, FRESH_NOW)])
        def absent = SnapshotJsonMapperSpec.referenceSnapshot()

        expect:
        AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(open), FRESH_NOW)
                .any {
                    it instanceof AlertCondition.RemoteGateOpen && it.target() == 'origin'
                }
        !AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(closed), FRESH_NOW)
                .any { it instanceof AlertCondition.RemoteGateOpen }
        // referenceSnapshot's own remote entry is closed, so no line either.
        !AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(absent), FRESH_NOW)
                .any { it instanceof AlertCondition.RemoteGateOpen }
    }

    def "a gate open past the sustained-open threshold flags sustainedOpen"() {
        given:
        def justOpened = snapshotWithRemote([origin: new RemoteHealth(
                    'origin', true, FRESH_NOW.minusSeconds(60), 'boom', FRESH_NOW.plusSeconds(60), 1, null)])
        def longOpen = snapshotWithRemote([origin: new RemoteHealth(
                    'origin', true, FRESH_NOW.minusSeconds(3661), 'boom', FRESH_NOW.plusSeconds(60), 40, null)])

        expect:
        !(AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(justOpened), FRESH_NOW)
                .find {
                    it instanceof AlertCondition.RemoteGateOpen
                } as AlertCondition.RemoteGateOpen).sustainedOpen()
        (AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(longOpen), FRESH_NOW)
                .find {
                    it instanceof AlertCondition.RemoteGateOpen
                } as AlertCondition.RemoteGateOpen).sustainedOpen()
    }

    // Pins the exact threshold boundary (1 hour): elapsed exactly equal to the threshold already
    // counts as sustained (>=, not >), one tick under it does not.
    def "an outage open for exactly the sustained-open threshold is already flagged sustained"() {
        given:
        def exactlyAtThreshold = snapshotWithRemote([origin: new RemoteHealth(
                    'origin', true, FRESH_NOW.minusSeconds(3600), 'boom', FRESH_NOW.plusSeconds(60), 1, null)])
        def oneSecondUnder = snapshotWithRemote([origin: new RemoteHealth(
                    'origin', true, FRESH_NOW.minusSeconds(3599), 'boom', FRESH_NOW.plusSeconds(60), 1, null)])

        expect:
        (AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(exactlyAtThreshold), FRESH_NOW)
                .find {
                    it instanceof AlertCondition.RemoteGateOpen
                } as AlertCondition.RemoteGateOpen).sustainedOpen()
        !(AlertConditionEvaluator.evaluate(new DaemonSnapshotView.Fresh(oneSecondUnder), FRESH_NOW)
                .find {
                    it instanceof AlertCondition.RemoteGateOpen
                } as AlertCondition.RemoteGateOpen).sustainedOpen()
    }

    private static Snapshot snapshotWithRemote(Map<String, RemoteHealth> remote) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), base.vitals(), base.tracker(), remote)
    }

    private static Snapshot snapshotWithConsecutiveFailures(int count) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def tracker = new TrackerHealth(base.tracker().lastSuccessAt(), count)
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), base.vitals(), tracker, [:])
    }

    private static Snapshot snapshotWithReaper(Instant lastRunAt, int restartCount) {
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def reaper = new ReaperVital(lastRunAt, restartCount, base.vitals().reaper().intervalSeconds())
        def vitals = new VitalsSnapshot(base.vitals().heartbeat(), reaper, base.vitals().janitor())
        return withVitals(base, vitals)
    }

    private static Snapshot withVitals(Snapshot base, VitalsSnapshot vitals) {
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), vitals, base.tracker(), [:])
    }
}
