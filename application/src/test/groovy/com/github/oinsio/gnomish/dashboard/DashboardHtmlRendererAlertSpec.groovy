package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.NOW
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.WRITTEN_AT
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.healthySnapshot
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.snapshotWithDegradedReaper
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.snapshotWithLongIdleBlocked
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.snapshotWithOccupiedSlotsDeadHeartbeat
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.snapshotWithTrackerFailures
import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Duration
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the status card surfaces every {@link AlertCondition} rule 1-5
 * that {@link AlertConditionEvaluator} finds true of the rendered snapshot,
 * evaluated against the page's own {@code generatedAt}: each fires as its
 * own short alarm-palette {@code status__alert} line inside the card.
 *
 * <p>redesign-dashboard changed where alerts live, not what fires them: the
 * sandbox-hygiene conditions now surface here too, and the section-level
 * highlight classes ({@code daemon-alert}, {@code sandbox-alert}) and the
 * full-viewport staleness banner they had to stay distinct from are gone.
 *
 * FR4 of add-dashboard-page; FR1, FR2 of redesign-dashboard.
 */
class DashboardHtmlRendererAlertSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    @Unroll
    def "#label alert renders as its own alarm line in the status card"() {
        when:
        def html = renderer.render(view, emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then:
        html.contains('<div class="status__alert">' + label + '</div>')

        where:
        label | view
        'dead daemon' | new DaemonSnapshotView.DeadDaemon(healthySnapshot())
        'occupied slots not heartbeating' | new DaemonSnapshotView.Fresh(snapshotWithOccupiedSlotsDeadHeartbeat())
        'idle-blocked too long' | new DaemonSnapshotView.Fresh(snapshotWithLongIdleBlocked())
        'tracker failures' | new DaemonSnapshotView.Fresh(snapshotWithTrackerFailures())
        'reaper degraded' | new DaemonSnapshotView.Fresh(snapshotWithDegradedReaper())
    }

    def "two simultaneous alerts render as two lines, not just the first one shown"() {
        given: 'tracker failures and a degraded reaper both fire on the same snapshot'
        def base = snapshotWithTrackerFailures()
        def vitals = new VitalsSnapshot(
                base.vitals().heartbeat(), new ReaperVital(WRITTEN_AT.minusSeconds(1000), 0, 300L), base.vitals().janitor())
        def snapshot = new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), vitals, base.tracker())

        when:
        def html = renderer.render(new DaemonSnapshotView.Fresh(snapshot), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then: 'each condition gets its own line, in rule order'
        html.contains('<div class="status__alert">'
                + DashboardAlertLabels.label(new AlertCondition.TrackerFailuresPresent())
                + '</div>\n<div class="status__alert">'
                + DashboardAlertLabels.label(new AlertCondition.ReaperDegraded()) + '</div>')
    }

    // FR1, FR2 of redesign-dashboard: hygiene alerts moved out of the hygiene block into this card.
    def "a sandbox-hygiene alert surfaces in the status card, and the hygiene block stays unstyled"() {
        given: 'a sweep whose last tick is far past three times its own cadence'
        def sweep = new SweepVital(NOW.minusSeconds(3600), 300L, new SweepCounts(0, 0, 0, 0, 0, 0), [], 0, 0)

        when:
        def html = renderer.render(
                new DaemonSnapshotView.Fresh(healthySnapshot()), emptyHistory(), neverFetchedBoard(),
                new SandboxHygieneView(sweep, []), NOW, null)

        then:
        html.contains('<div class="status__alert">'
                + DashboardAlertLabels.label(new AlertCondition.SweepTickOverdue()) + '</div>')

        and: 'the hygiene block carries no alert class of its own — it is the quietest block on the page'
        !html.contains('sandbox-alert')
        html.contains('<section class="card" id="hygiene">')
    }

    def "a healthy fresh snapshot renders no alarm line at all"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Fresh(healthySnapshot()), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then: 'the status card holds the state and the stats, and nothing in the alarm palette'
        !markup(html).contains('status__alert')
        html.contains('Daemon running')
    }

    // FR3 of redesign-dashboard: a dead daemon (layer 1) and a dead renderer (layer 2) stay distinct
    //     surfaces — the alarm line lives in the card, the freshness strip above every card.
    def "the daemon alarm line and the freshness strip stay separate surfaces"() {
        when: 'a stale-daemon view is rendered in watch mode, engaging both layers'
        def html = renderer.render(
                new DaemonSnapshotView.DeadDaemon(healthySnapshot()), emptyHistory(), neverFetchedBoard(), noSweepData(), NOW,
                Duration.ofSeconds(10))

        then: 'the daemon condition is a line in the card; the renderer\'s own freshness is the strip'
        html.contains('<div class="status__alert">dead daemon</div>')
        html.contains('<div class="freshness" id="freshness" data-state="fresh"')

        and: 'the strip never borrows the alert class, and the card never borrows the strip id'
        !html.contains('class="freshness status__alert"')
        !html.contains('id="freshness" class="status__alert"')

        and: 'no full-viewport staleness banner exists anywhere on the page'
        !html.contains('staleness-banner')
    }
}
