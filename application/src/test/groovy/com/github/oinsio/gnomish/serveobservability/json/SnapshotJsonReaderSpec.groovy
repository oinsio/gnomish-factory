package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.KeptEnvironmentEntry
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link SnapshotJsonReader} parses the v1 snapshot JSON contract back
 * into a {@link Snapshot} carrying every nested field through — the read-side
 * counterpart of {@link SnapshotJsonMapperSpec}. Each nested {@code fromX} helper
 * is pinned by a concrete leaf assertion, so a helper that dropped its nested
 * record (returned {@code null}) fails a value equality, and the one nullable
 * instant ({@code tracker.lastSuccessAt}) is asserted in BOTH its present and
 * absent forms so neither branch of {@code fromInstant} can be flipped unseen.
 *
 * FR3, FR4 of add-dashboard-page.
 */
class SnapshotJsonReaderSpec extends Specification {

    def mapper = new SnapshotJsonMapper()
    def reader = new SnapshotJsonReader()

    // FR3: every nested field of a fully-populated snapshot must survive parsing.
    // A fromX helper replaced to return null drops a nested record, which a leaf
    // equality below then catches: fromFeed/fromFeedState (feed.*), fromSlots
    // (slots.*), fromVitals/fromHeartbeat/fromHeartbeatState (vitals.heartbeat.*),
    // fromReaper (vitals.reaper.*), fromJanitor (vitals.janitor.*), fromTracker and
    // fromInstant's present-branch (tracker.lastSuccessAt).
    def "parsing a fully-populated snapshot carries every nested field through to its value"() {
        given:
        def expected = SnapshotJsonMapperSpec.referenceSnapshot()

        when:
        def parsed = reader.read(mapper.serialize(expected))

        then: 'top-level scalars, instance, and lifecycle'
        parsed.version() == 1
        parsed.writtenAt() == Instant.parse('2026-08-02T09:00:00Z')
        parsed.intervalSeconds() == 30L
        parsed.instance() == expected.instance()
        parsed.lifecycle() == new LifecycleState.Running()

        and: 'feed — fromFeed, fromFeedState, and its two instants'
        parsed.feed().state() == FeedPhase.FILLING
        parsed.feed().since() == Instant.parse('2026-08-02T08:59:50Z')
        parsed.feed().lastPollAt() == Instant.parse('2026-08-02T08:59:55Z')
        parsed.feed().openFronts() == 2
        parsed.feed().wipLimit() == 3

        and: 'slots — fromSlots and each mapped entry'
        parsed.slots().capacity() == 3
        parsed.slots().entries().size() == 2
        parsed.slots().entries()[0].taskId() == 'task-42'
        parsed.slots().entries()[0].stage() == 'implement'
        parsed.slots().entries()[0].attempt() == 1
        parsed.slots().entries()[0].since() == Instant.parse('2026-08-02T08:45:00Z')
        parsed.slots().entries()[1].taskId() == 'task-43'
        parsed.slots().entries()[1].stage() == null
        parsed.slots().entries()[1].attempt() == 0
        parsed.slots().entries()[1].since() == Instant.parse('2026-08-02T08:50:00Z')

        and: 'vitals heartbeat — fromVitals, fromHeartbeat, fromHeartbeatState'
        parsed.vitals().heartbeat().state() == HeartbeatState.RUNNING
        parsed.vitals().heartbeat().lastTickAt() == Instant.parse('2026-08-02T08:59:58Z')
        parsed.vitals().heartbeat().heldClaims() == 2

        and: 'vitals reaper — fromReaper'
        parsed.vitals().reaper().lastRunAt() == Instant.parse('2026-08-02T08:55:00Z')
        parsed.vitals().reaper().restartCount() == 0
        parsed.vitals().reaper().intervalSeconds() == 300L

        and: 'vitals janitor — fromJanitor'
        parsed.vitals().janitor().lastRunAt() == Instant.parse('2026-08-02T08:00:00Z')

        and: 'vitals sweep — fromSweep, fromSweepCounts, and the kept inventory (NFR-O1)'
        parsed.vitals().sweep().lastTickAt() == Instant.parse('2026-08-02T08:58:00Z')
        parsed.vitals().sweep().intervalSeconds() == 300L
        parsed.vitals().sweep().counts() == new SweepCounts(4, 2, 1, 1, 3, 0)
        parsed.vitals().sweep().kept() == [
            new KeptEnvironmentEntry('task-40', 172800L, 432000L),
            new KeptEnvironmentEntry('task-41', 518400L, 86400L)
        ]
        parsed.vitals().sweep().keptTotal() == 2
        parsed.vitals().sweep().consecutiveSkippedTicks() == 0

        and: 'tracker — fromTracker and fromInstant present-branch'
        parsed.tracker().lastSuccessAt() == Instant.parse('2026-08-02T08:59:55Z')
        parsed.tracker().consecutiveFailures() == 0
    }

    // FR4: tracker.lastSuccessAt is the one nullable instant. A null wire value must
    // parse to null. This pins fromInstant's null-branch and kills its
    // NegateConditionalsMutator: negated (instant != null ? null : parse(instant)),
    // a null input would take the Instant.parse(null) arm and throw rather than
    // yielding null. Paired with the present-branch assertion above, both arms of
    // the conditional are now observable.
    def "a null tracker lastSuccessAt is parsed as null"() {
        given:
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def neverSucceeded = new Snapshot(
                base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), base.vitals(),
                new TrackerHealth(null, 7))

        when:
        def parsed = reader.read(mapper.serialize(neverSucceeded))

        then:
        parsed.tracker().lastSuccessAt() == null
        parsed.tracker().consecutiveFailures() == 7
    }

    // NFR-O1 of add-serve-sandbox-lifecycle: a document written before the first tick renders
    //     vitals.sweep as null and must read back as absent, never as a tick that counted zero.
    def "a null vitals sweep is parsed as absent"() {
        given:
        def base = SnapshotJsonMapperSpec.referenceSnapshot()
        def vitals = base.vitals()
        def preSweep = new Snapshot(
                base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(),
                new VitalsSnapshot(vitals.heartbeat(), vitals.reaper(), vitals.janitor()), base.tracker())

        when:
        def parsed = reader.read(mapper.serialize(preSweep))

        then:
        parsed.vitals().sweep() == null
    }

    // NFR-O1: a document from a build that predates this contract omits the field ENTIRELY, which
    //     must read back the same way — the dashboard's "no sweep data yet" state, not a crash.
    def "a document with no vitals sweep field at all is parsed as absent"() {
        given:
        def json = mapper.serialize(SnapshotJsonMapperSpec.referenceSnapshot())
        def withoutSweep = json.replaceAll(/(?s),\s*"sweep" : \{.*?\n {4}}/, '')

        when:
        def parsed = reader.read(withoutSweep)

        then:
        parsed.vitals().sweep() == null
        parsed.vitals().janitor().lastRunAt() == Instant.parse('2026-08-02T08:00:00Z')
    }

    // FR3: fromFeedState maps every wire value to its FeedPhase — each switch arm.
    def "feed state parses each wire value to its FeedPhase"() {
        expect:
        reader.read(mapper.serialize(withFeedPhase(phase))).feed().state() == phase

        where:
        phase << [
            FeedPhase.FILLING,
            FeedPhase.IDLE_EMPTY,
            FeedPhase.IDLE_BLOCKED,
            FeedPhase.FULL
        ]
    }

    // FR3: fromHeartbeatState maps every wire value to its HeartbeatState — each arm.
    def "heartbeat state parses each wire value to its HeartbeatState"() {
        expect:
        reader.read(mapper.serialize(withHeartbeatState(state))).vitals().heartbeat().state() == state

        where:
        state << [
            HeartbeatState.IDLE,
            HeartbeatState.RUNNING,
            HeartbeatState.DIED
        ]
    }

    // FR4: fromLifecycle maps every wire value back to its LifecycleState — each switch arm,
    // including "stopped", whose reason is the one lifecycle field that travels on the wire.
    // SnapshotJsonMapper <-> SnapshotJsonReader is a declared manually-synchronized pair whose
    // invariant names LifecycleState (.claude/rules/manual-sync-pairs.md), so a constant mapped
    // on only one side must fail here rather than in production.
    def "lifecycle state parses each wire value back to its LifecycleState"() {
        expect:
        reader.read(mapper.serialize(withLifecycle(lifecycle))).lifecycle() == lifecycle

        where:
        lifecycle << [
            new LifecycleState.Running(),
            new LifecycleState.Draining(),
            new LifecycleState.Stopping(),
            new LifecycleState.Stopped('signal')
        ]
    }

    // FR4: "stopped" without a reason is a malformed document, not a Stopped with a null reason —
    // requireReason refuses it, since LifecycleState.Stopped's reason is never blank.
    def "a stopped lifecycle with no reason is rejected"() {
        given:
        def json = mapper.serialize(withLifecycle(new LifecycleState.Stopped('signal')))
                .replace('"reason" : "signal"', '"reason" : null')

        when:
        reader.read(json)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('lifecycle.reason')
    }

    // FR4: an unrecognized lifecycle wire token fails loudly rather than silently defaulting —
    // the default arm the class javadoc promises for a future contract version.
    def "an unknown lifecycle wire token is rejected"() {
        given:
        def json = mapper.serialize(SnapshotJsonMapperSpec.referenceSnapshot())
                .replaceFirst(/"state" : "running"/, '"state" : "hibernating"')

        when:
        reader.read(json)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('unknown lifecycle.state: hibernating')
    }

    private static Snapshot withLifecycle(LifecycleState lifecycle) {
        def s = SnapshotJsonMapperSpec.referenceSnapshot()
        return new Snapshot(s.version(), s.writtenAt(), s.intervalSeconds(), s.instance(),
                lifecycle, s.feed(), s.slots(), s.vitals(), s.tracker())
    }

    private static Snapshot withFeedPhase(FeedPhase phase) {
        def s = SnapshotJsonMapperSpec.referenceSnapshot()
        def f = s.feed()
        def feed = new FeedSnapshot(phase, f.since(), f.lastPollAt(), f.openFronts(), f.wipLimit())
        return new Snapshot(s.version(), s.writtenAt(), s.intervalSeconds(), s.instance(),
                s.lifecycle(), feed, s.slots(), s.vitals(), s.tracker())
    }

    private static Snapshot withHeartbeatState(HeartbeatState state) {
        def s = SnapshotJsonMapperSpec.referenceSnapshot()
        def v = s.vitals()
        def hb = v.heartbeat()
        def vitals = new VitalsSnapshot(new HeartbeatVital(state, hb.lastTickAt(), hb.heldClaims()), v.reaper(), v.janitor())
        return new Snapshot(s.version(), s.writtenAt(), s.intervalSeconds(), s.instance(),
                s.lifecycle(), s.feed(), s.slots(), vitals, s.tracker())
    }
}
