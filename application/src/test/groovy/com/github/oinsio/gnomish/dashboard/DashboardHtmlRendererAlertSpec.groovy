package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotEntry
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the daemon section visually flags every {@link AlertCondition}
 * rule 1-5 that {@link AlertConditionEvaluator} finds true of the rendered
 * snapshot (task 3.4): the {@code daemon-alert} highlight class and a
 * "daemon alert:" label naming the fired condition(s), evaluated against
 * the page's own {@code generatedAt}. Asserts the highlight is absent for a
 * clean/healthy snapshot, and that its class name and wording never overlap
 * with {@link DashboardHtmlRendererStalenessSpec}'s page-staleness banner
 * (UX3).
 *
 * FR4, UX3 of add-dashboard-page (design D3).
 */
class DashboardHtmlRendererAlertSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant WRITTEN_AT = Instant.parse('2026-08-06T09:00:00Z')
    private static final Instant NOW = WRITTEN_AT.plusSeconds(60)

    @Unroll
    def "#label alert renders the daemon-alert highlight and its label"() {
        when:
        def html = renderer.render(view, emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then:
        html.contains('class="daemon-alert"')
        html.contains('daemon alert:')
        html.contains(label)

        where:
        label | view
        'dead daemon' | new DaemonSnapshotView.DeadDaemon(healthySnapshot())
        'occupied slots not heartbeating' | new DaemonSnapshotView.Fresh(snapshotWithOccupiedSlotsDeadHeartbeat())
        'idle-blocked too long' | new DaemonSnapshotView.Fresh(snapshotWithLongIdleBlocked())
        'tracker failures' | new DaemonSnapshotView.Fresh(snapshotWithTrackerFailures())
        'reaper degraded' | new DaemonSnapshotView.Fresh(snapshotWithDegradedReaper())
    }

    def "two simultaneous alerts are comma-separated, not just the first one shown"() {
        given: 'tracker failures and a degraded reaper both fire on the same snapshot'
        def base = snapshotWithTrackerFailures()
        def tracker = base.tracker()
        def vitals = new VitalsSnapshot(
                base.vitals().heartbeat(), new ReaperVital(WRITTEN_AT.minusSeconds(1000), 0, 300L), base.vitals().janitor())
        def snapshot = new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), vitals, tracker)

        when:
        def html = renderer.render(new DaemonSnapshotView.Fresh(snapshot), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then: 'the two labels are joined by exactly one comma, with no leading comma before the first'
        html.contains('daemon alert: '
                + DashboardAlertLabels.label(new AlertCondition.TrackerFailuresPresent())
                + ', ' + DashboardAlertLabels.label(new AlertCondition.ReaperDegraded()) + '</p>')
    }

    def "a healthy fresh snapshot renders no daemon-alert highlight or label"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Fresh(healthySnapshot()), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then: 'the static CSS rule is always baked in, but it is never applied and no alert text appears'
        !html.contains('class="daemon-alert"')
        !html.contains('daemon alert:')
    }

    def "the daemon alert highlight and wording never overlap the page-staleness banner's"() {
        given:
        def cadence = java.time.Duration.ofSeconds(10)

        when: 'a stale-daemon view is rendered in watch mode, triggering both layers'
        def html = renderer.render(
                new DaemonSnapshotView.DeadDaemon(healthySnapshot()), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, cadence)

        then: 'both layers are present, using different CSS classes and different wording'
        html.contains('class="daemon-alert"')
        html.contains('id="staleness-banner"')
        html.contains('daemon alert:')
        html.contains('view is stale')

        and: 'the banner never borrows the daemon-alert class, and the section never borrows the banner id'
        !html.contains('id="daemon-alert"')
        !html.contains('class="staleness-banner"')

        and: 'the banner keeps its own distinct wording, not the daemon alert\'s'
        !html.contains('view is stale &mdash; daemon alert')
    }

    private static Snapshot healthySnapshot() {
        new Snapshot(
                1,
                WRITTEN_AT,
                30L,
                new InstanceInfo('gnome-1-abcd', 'host1', '1.0.0'),
                new LifecycleState.Running(),
                new FeedSnapshot(FeedPhase.FILLING, WRITTEN_AT, WRITTEN_AT, 0, 3),
                new SlotsSnapshot(3, []),
                new VitalsSnapshot(
                        new HeartbeatVital(HeartbeatState.RUNNING, WRITTEN_AT, 0),
                        new ReaperVital(WRITTEN_AT, 0, 300L),
                        new JanitorVital(WRITTEN_AT)),
                new TrackerHealth(WRITTEN_AT, 0))
    }

    private static Snapshot snapshotWithOccupiedSlotsDeadHeartbeat() {
        def base = healthySnapshot()
        def slots = new SlotsSnapshot(3, [
            new SlotEntry('task-1', 'implement', 1, WRITTEN_AT)
        ])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.DIED, WRITTEN_AT, 0), base.vitals().reaper(), base.vitals().janitor())
        return withSlotsAndVitals(base, slots, vitals)
    }

    private static Snapshot snapshotWithLongIdleBlocked() {
        def base = healthySnapshot()
        def feed = new FeedSnapshot(FeedPhase.IDLE_BLOCKED, NOW.minusSeconds(31 * 60), WRITTEN_AT, 0, 3)
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), feed, base.slots(), base.vitals(), base.tracker())
    }

    private static Snapshot snapshotWithTrackerFailures() {
        def base = healthySnapshot()
        def tracker = new TrackerHealth(WRITTEN_AT, 3)
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), base.vitals(), tracker)
    }

    private static Snapshot snapshotWithDegradedReaper() {
        def base = healthySnapshot()
        def vitals = new VitalsSnapshot(
                base.vitals().heartbeat(), new ReaperVital(WRITTEN_AT.minusSeconds(1000), 0, 300L), base.vitals().janitor())
        return withSlotsAndVitals(base, base.slots(), vitals)
    }

    private static Snapshot withSlotsAndVitals(Snapshot base, SlotsSnapshot slots, VitalsSnapshot vitals) {
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), slots, vitals, base.tracker())
    }
}
