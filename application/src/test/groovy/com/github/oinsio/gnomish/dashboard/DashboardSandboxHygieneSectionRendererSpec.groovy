package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.serveobservability.KeptEnvironmentEntry
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import java.time.Instant
import spock.lang.Specification

/**
 * {@link DashboardSandboxHygieneSectionRenderer}, task 6.3/6.4 of add-serve-sandbox-lifecycle
 * (NFR-O3, UX1, UX2): the four-group breakdown mapped over the six verdict categories, the kept
 * inventory with its reap margin and truncation note, the recent-actions table, the alert
 * highlight — and the honest degradation when either input is missing.
 */
class DashboardSandboxHygieneSectionRendererSpec extends Specification {

    static final Instant TICK_AT = Instant.parse('2026-08-06T09:00:00Z')

    def renderer = new DashboardSandboxHygieneSectionRenderer()

    private static SweepVital vital(
            SweepCounts counts = SweepCounts.NONE,
            List<KeptEnvironmentEntry> kept = [],
            int keptTotal = 0,
            int consecutiveSkipped = 0) {
        new SweepVital(TICK_AT, 300L, counts, kept, keptTotal, consecutiveSkipped)
    }

    private static SweepActionRow row(
            SweepVerdictCategory category = SweepVerdictCategory.STOPPED_ORPHAN,
            String mode = 'tracked',
            String object = 'gnomish-task-1-box',
            Long ageSeconds = 900L) {
        new SweepActionRow(TICK_AT, object, 'main-box', mode, 'task-1', category, 'unowned running', ageSeconds)
    }

    private String render(SandboxHygieneView view, Instant now = TICK_AT) {
        def out = new StringBuilder()
        renderer.append(out, view, now)
        out.toString()
    }

    // NFR-O3, FR3 of add-dashboard-page: an absent vital and an empty ledger render an honest
    //     "nothing swept here yet", never a table of zeroes that would read as a clean host.
    def "no sweep data at all renders the honest empty state"() {
        given:
        def html = render(SandboxHygieneView.absent())

        expect:
        html.contains('<section id="sandbox-hygiene"')
        html.contains('no sweep data yet')
        !html.contains('<table>')
        !html.contains('class="sandbox-alert"')
    }

    // UX1: "what was cleaned, what was stopped, what was checked and left untouched, and whether
    //      the sweep is silently skipping" — the four groups over the six categories.
    def "the last tick's breakdown groups the six categories into the four UX groups"() {
        given: 'distinct counts so no two groups can be confused for one another'
        def counts = new SweepCounts(4, 2, 3, 5, 6, 1)

        when:
        def html = render(new SandboxHygieneView(vital(counts), [], 0))

        then: 'cleaned = disposedAged + disposedReconstructible = 11, stopped = 3,'
        // 'checked and untouched = checkedAlive + keptUnderThreshold = 6, skipped = 1'
        html.contains('<td>11</td><td>3</td><td>6</td><td>1</td>')

        and: 'the four group headers name the questions, not the internal category vocabulary'
        html.contains('<th>cleaned</th>')
        html.contains('<th>stopped</th>')
        html.contains('<th>checked and untouched</th>')
        html.contains('<th>skipped without verdict</th>')

        and: 'the tick the numbers came from is stamped'
        html.contains('last sweep tick at 2026-08-06T09:00:00Z')
    }

    // NFR-O1, UX1: the inventory answers "what waits for resume, and for how much longer".
    def "the kept inventory renders each task with its age and time to reap"() {
        given:
        def kept = [
            new KeptEnvironmentEntry('task-40', 172800L, 432000L),
            new KeptEnvironmentEntry('task-41', 518400L, 86400L)
        ]

        when:
        def html = render(new SandboxHygieneView(vital(SweepCounts.NONE, kept, 2), [], 0))

        then:
        html.contains('<th>kept task</th><th>age</th><th>time to reap</th>')
        html.contains('<td>task-40</td><td>2d</td><td>5d</td>')
        html.contains('<td>task-41</td><td>6d</td><td>1d</td>')

        and: 'nothing was dropped, so no truncation note appears'
        !html.contains('kept environments</p>')
    }

    // NFR-O1: truncation is STATED, so twenty of thirty-four never reads as twenty.
    def "a truncated kept inventory states how many it is showing"() {
        given:
        def kept = [
            new KeptEnvironmentEntry('task-40', 60L, 60L)
        ]

        when:
        def html = render(new SandboxHygieneView(vital(SweepCounts.NONE, kept, 34), [], 0))

        then:
        html.contains('showing 1 of 34 kept environments')
    }

    def "an empty kept inventory says so rather than rendering an empty table"() {
        expect:
        render(new SandboxHygieneView(vital(), [], 0)).contains('no kept environments')
    }

    // UX1: the actions table answers "what was cleaned yesterday", per object.
    def "the recent-actions table renders every field of each action"() {
        when:
        def html = render(new SandboxHygieneView(vital(), [row()], 1))

        then:
        html.contains('<th>at</th><th>object</th><th>role</th><th>mode</th><th>task</th>')
        html.contains('<td>2026-08-06T09:00:00Z</td><td>gnomish-task-1-box</td><td>main-box</td>'
                + '<td>tracked</td><td>task-1</td><td>stopped</td><td>unowned running</td><td>15m</td>')
    }

    // NFR-O2: a stop verdict measures no age, so the cell says so rather than printing a zero.
    def "an action with no measured age renders a dash, not a zero"() {
        when:
        def html = render(new SandboxHygieneView(vital(), [
            row(SweepVerdictCategory.STOPPED_ORPHAN, 'tracked', 'b', null)
        ], 1))

        then:
        html.contains('<td>&mdash;</td>')
        !html.contains('<td>0s</td>')
    }

    def "a truncated actions table states how many it is showing"() {
        expect:
        render(new SandboxHygieneView(vital(), [row()], 41)).contains('showing 1 of 41 sweep actions in the window')
    }

    def "an empty actions table says so rather than rendering an empty table"() {
        expect:
        render(new SandboxHygieneView(vital(), [], 0)).contains('no recent sweep actions')
    }

    // NFR-O3: the two halves degrade separately — a snapshot from a build without the vital still
    //     shows the ledger's actions, and says why the breakdown is missing.
    def "a missing snapshot vital still renders the ledger's actions"() {
        when:
        def html = render(new SandboxHygieneView(null, [row()], 1))

        then:
        html.contains('last tick: no snapshot vital')
        html.contains('<td>gnomish-task-1-box</td>')
        !html.contains('no sweep data yet')

        and: 'a missing vital carries no inventory to render either'
        !html.contains('kept task')
        !html.contains('no kept environments')
    }

    // UX2: the incident reads as a dead instance, names the box and the task, and highlights the
    //      section — distinct in wording from the daemon section's own alert line.
    def "a tracked stopped-orphan highlights the section and names the box and task"() {
        when:
        def html = render(new SandboxHygieneView(vital(), [row()], 1))

        then:
        html.contains('<section id="sandbox-hygiene" class="sandbox-alert">')
        html.contains('sandbox alert: an instance died or hung: stopped gnomish-task-1-box '
                + 'of task task-1 (unowned running)')
    }

    // UX2: a routine manual age-stop appears in the table but raises nothing.
    def "a manual age-stop appears in the table without raising an alert"() {
        when:
        def html = render(new SandboxHygieneView(vital(), [
            row(SweepVerdictCategory.STOPPED_ORPHAN, 'manual')
        ], 1))

        then:
        html.contains('<td>manual</td>')
        !html.contains('class="sandbox-alert"')
        !html.contains('sandbox alert:')
    }

    // NFR-O3: several conditions render as one line, separated so each stays readable.
    def "several conditions render on one alert line"() {
        when:
        def html = render(
                new SandboxHygieneView(vital(SweepCounts.NONE, [], 0, 3), [row()], 1), TICK_AT.plusSeconds(7200))

        then:
        html.contains('sandbox alert: sandbox sweep not running; '
                + 'sandbox cleanup stalled: 3 consecutive ticks reached no claim verdict; '
                + 'an instance died or hung')
    }

    // NFR-S1 of add-dashboard-page: ledger text reaches the page only through the escaper.
    def "action fields are escaped before reaching the page"() {
        given:
        def hostile = new SweepActionRow(
                TICK_AT, '<script>alert(1)</script>', 'main-box', 'tracked', 'task-1',
                SweepVerdictCategory.DISPOSED_AGED, 'a & b', null)

        when:
        def html = render(new SandboxHygieneView(vital(), [hostile], 1))

        then:
        html.contains('&lt;script&gt;alert(1)&lt;/script&gt;')
        html.contains('a &amp; b')
        !html.contains('<script>alert(1)</script>')
    }
}
