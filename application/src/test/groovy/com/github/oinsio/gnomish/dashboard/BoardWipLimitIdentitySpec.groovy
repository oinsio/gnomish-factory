package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.RecordingReadOnlyTracker
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.board.BoardComposition
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.json.BoardJsonMapper
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The identity spec of the {@code BoardModel} single-owner row (design D13, task 7.3): one model
 * built through the real {@link BoardComposition#compose} from one tracker state and one
 * configured WIP limit, fed unchanged to the real {@link BoardJsonMapper} and the real {@link
 * DashboardHtmlRenderer}. The limit the JSON prints, the denominator the status card's WIP stat
 * renders and the limit the WIP-held Ready row was judged against are one value — not three
 * copies that happen to agree. The contrast row (limit 4, same tracker state) shows the held row
 * is held by that limit and nothing else: raise it by one and the same row is eligible while the
 * JSON and the page move with it.
 *
 * <p>FR12, FR13, FR14 of supervise-daemon-loops-and-embed-dashboard (dashboard-page "Limit
 * matches the held rows").
 */
class BoardWipLimitIdentitySpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')
    private static final int READY_LIMIT = 50

    def "FR12/FR13/FR14 of supervise-daemon-loops-and-embed-dashboard, Limit matches the held rows: JSON wipLimit, WIP denominator and the held row's limit are one value (limit #wipLimit)"() {
        given: 'three open fronts (two working, one waiting for a human) and one fresh ready task'
        def tracker = new RecordingReadOnlyTracker(
                [
                    new ReadyTask(new TaskRef('r-1'), AbortFacts.none(), false, false, UntrustedText.tracker('Add widgets'))
                ],
                [
                    working('w-1'),
                    working('w-2'),
                    new OpenTask(new TaskRef('h-1'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null,
                    UntrustedText.tracker('Needs a decision'))
                ])
        def trackerConfig = new TrackerConfig('fixture', 3, TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL,
                TrackerConfig.DEFAULT_HEARTBEAT_TTL_MULTIPLIER, wipLimit, [:])
        def trackerProperties = new FactoryProperties.Tracker(Duration.ofMinutes(2), Duration.ofHours(1))

        when: 'one model is composed exactly as the board and the dashboard compose it, then rendered by both renderers'
        def model = BoardComposition.compose(tracker, trackerConfig, trackerProperties, new VirtualClock(NOW), READY_LIMIT)
        def readyJson = new ObjectMapper().readTree(new BoardJsonMapper().serialize(model)).get('ready')
        def html = new DashboardHtmlRenderer().render(new DaemonSnapshotView.Absent(), emptyHistory(),
                new BoardSectionView(model, NOW, null), noSweepData(), NOW, null)
        def card = html.substring(html.indexOf('id="status"'), html.indexOf('id="attention"'))

        then: 'the ready row was judged against this limit: held exactly when three open fronts reach it'
        (model.readyRows()[0].eligibilityReason() instanceof EligibilityReason.WipHeld) == held
        readyJson.get('rows').get(0).get('eligibility').get('reason').textValue() == (held ? 'wipHeld' : null)
        readyJson.get('wipHeldCount').asInt() == (held ? 1 : 0)

        and: 'the JSON prints that same limit and open-front count'
        readyJson.get('wipLimit').asInt() == wipLimit
        readyJson.get('openFrontCount').asInt() == 3

        and: 'the status card renders that same limit as the WIP denominator'
        card.contains(">3 / ${wipLimit}</div>")
        card.contains("title=\"3 of ${wipLimit} open fronts: 2 working, 1 waiting for a human\"")

        where:
        wipLimit || held
        3 || true
        4 || false
    }

    private static OpenTask working(String id) {
        new OpenTask(new TaskRef(id), new TrackerTaskState.Working('gnome-1'),
                new ClaimVersion("marker-$id", NOW - Duration.ofMinutes(3), new ClaimEpoch(1)), UntrustedText.tracker("Working $id"))
    }
}
