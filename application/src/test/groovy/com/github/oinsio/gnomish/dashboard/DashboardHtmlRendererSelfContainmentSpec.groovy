package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures.snapshot
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.time.Instant
import java.time.LocalDate
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Verifies {@link DashboardHtmlRenderer} output stays self-contained and
 * within the composed surfaces (task 3.2): scans a fully-rendered page for
 * any external reference — {@code http://}, {@code https://},
 * protocol-relative URLs, and any {@code <script src=}/{@code <link href=}
 * tag — and checks that composed-surface strings, including a deliberately
 * credential-shaped title, appear on the page verbatim (escaped) and only
 * where the section renderers put them, with no unrelated
 * credential/secret-looking substrings appearing anywhere else.
 *
 * FR2, NFR-S1, M1 of add-dashboard-page.
 */
class DashboardHtmlRendererSelfContainmentSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    private static final Pattern EXTERNAL_ATTR_REF =
    Pattern.compile('(?i)(src|href)\\s*=\\s*["\\\']?\\s*((https?:)?//)')

    def "no external references anywhere in a fully-composed page"() {
        given:
        def html = renderer.render(fullDaemonView(), fullHistory(), fullBoard(), GENERATED_AT, null)

        expect:
        !html.contains('http://')
        !html.contains('https://')
        !EXTERNAL_ATTR_REF.matcher(html).find()
    }

    def "no <script src=...> or <link href=...> tags — all styling and scripting stays inline"() {
        given:
        def html = renderer.render(fullDaemonView(), fullHistory(), fullBoard(), GENERATED_AT, null)

        expect:
        !(html.toLowerCase() =~ /<script[^>]+src\s*=/)
        !(html.toLowerCase() =~ /<link[^>]+href\s*=/)
        // the page still ships its own inline styling, proving the check above is not vacuous
        html.contains('<style>')
    }

    def "no credential- or secret-looking substrings leak into the page"() {
        given:
        def html = renderer.render(fullDaemonView(), fullHistory(), fullBoard(), GENERATED_AT, null)
        def lower = html.toLowerCase()

        expect:
        !lower.contains('password')
        !lower.contains('secret')
        !lower.contains('authorization:')
        !lower.contains('token=')
    }

    def "a composed-surface title carrying a credential-shaped string is rendered verbatim, escaped, exactly once"() {
        given: 'a task title that looks like it might be a leaked secret, injected deliberately as fixture data'
        def canary = 'password=hunter2 <script>alert(1)</script>'
        def escapedCanary = DashboardHtmlFormatter.escape(canary)
        def readyRows = [
            new ReadyRow(new TaskRef('task-canary'), canary, false, null)
        ]
        def board = new BoardSectionView(
                new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), false, GENERATED_AT),
                GENERATED_AT,
                null)

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then: 'the composed-surface string appears, but only in its escaped form and exactly once'
        !html.contains(canary)
        countOccurrences(html, escapedCanary) == 1

        and: 'it does not leak as a live <script> tag'
        !(html.toLowerCase() =~ /<script>alert/)
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0
        int index = 0
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++
            index += needle.length()
        }
        count
    }

    private static DaemonSnapshotView fullDaemonView() {
        new DaemonSnapshotView.Fresh(snapshot(new LifecycleState.Running()))
    }

    private static LedgerHistoryView fullHistory() {
        new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-04'), new OutcomeCounts(2, 0, 1, 0)),
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(3, 1, 0, 0))
                ],
                [claude: new LedgerTokenUsage(1000, 500, 0, 0)])
    }

    private static BoardSectionView fullBoard() {
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, new EligibilityReason.InBackoff(GENERATED_AT))
        ]
        def workingRows = [
            new WorkingRow(new TaskRef('task-2'), 'Working title', 'gnome-1', null)
        ]
        def awaitingRows = [
            new AwaitingHumanRow(new TaskRef('task-3'), 'Parked title', ParkReason.ESCALATION)
        ]
        def model = new BoardModel(readyRows, workingRows, awaitingRows, ReadySummary.tally(readyRows), false, GENERATED_AT)
        new BoardSectionView(model, GENERATED_AT, null)
    }
}
