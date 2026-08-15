package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
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
