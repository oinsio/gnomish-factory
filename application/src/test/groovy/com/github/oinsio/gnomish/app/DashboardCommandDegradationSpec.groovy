package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.testsupport.BaselineSnapshotFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The page's three data sources degrade independently (FR3 of add-dashboard-page; FR1, FR2 of
 * redesign-dashboard), proven through a one-shot {@link DashboardCommand} over real files in the
 * instance's serve directory — a snapshot, a ledger — and a recording read-only tracker: one
 * source is broken or missing at a time, the others are healthy, and each block is read from the
 * page the command wrote.
 *
 * <p>Implements FR3, NFR-R2 of add-dashboard-page; FR1, FR2 of redesign-dashboard; NFR-O3 of
 * add-serve-sandbox-lifecycle.
 */
class DashboardCommandDegradationSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE_NAME = 'dashboard-instance'
    /** The fixture command's fixed clock (DashboardCommandFixture). */
    private static final Instant NOW = Instant.parse('2026-08-06T00:00:00Z')
    private static final String DELIVERED_LINE = '{"version":1,"type":"taskOutcome","outcome":"delivered",' +
    '"tokensByModel":{"claude":{"input":10,"output":5,"cacheCreation":0,"cacheRead":0}}}'

    @TempDir
    Path tempDir

    Path projectDir
    Path factoryHome

    def setup() {
        projectDir = GnomishProjectFixture.writeGnomishProject(tempDir.resolve('project'))
        factoryHome = tempDir.resolve('gnomish-home')
    }

    private Path serveDir() {
        Files.createDirectories(factoryHome.resolve('projects/widgets/serve').resolve(INSTANCE_NAME))
    }

    private void writeSnapshot(Snapshot snapshot = BaselineSnapshotFixture.baselineSnapshot(NOW, new LifecycleState.Running())) {
        Files.writeString(ObservabilityPaths.snapshotFile(serveDir()), new SnapshotJsonMapper().serialize(snapshot))
    }

    private Path ledgerFile() {
        ObservabilityPaths.ledgerFile(serveDir(), LocalDate.parse('2026-08-06'))
    }

    /** A board with one task waiting for a human and one ready. */
    private static RecordingReadOnlyTracker populatedTracker() {
        new RecordingReadOnlyTracker(
                [
                    new ReadyTask(new TaskRef('t-1'), AbortFacts.none(), false, false, UntrustedText.tracker('Ready widget'))
                ],
                [
                    new OpenTask(new TaskRef('t-2'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null,
                    UntrustedText.tracker('Parked widget'))
                ])
    }

    private String render(RecordingReadOnlyTracker tracker) {
        def out = tempDir.resolve('page.html')
        DashboardCommandFixture.command(factoryHome, projectDir, tracker, DashboardCommandFixture.killingSleeper(), INSTANCE_NAME)
                .run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))
        Files.readString(out)
    }

    /** The markup of one block, from its id to the next block's id. */
    private static String block(String html, String id) {
        int start = html.indexOf(" id=\"${id}\"")
        assert start >= 0: "no block ${id}"
        int next = html.indexOf(' id="', start + 1)
        html.substring(start, next < 0 ? html.indexOf('<script>', start) : next)
    }

    // Scenario: observability/dashboard-page / Tracker outage degrades only the board
    def "FR3: a tracker outage degrades both board-fed blocks only, exits zero, and never retries"() {
        given: 'a healthy snapshot and ledger, and a tracker that cannot be read'
        writeSnapshot()
        Files.writeString(ledgerFile(), DELIVERED_LINE + '\n')
        def tracker = new RecordingReadOnlyTracker([], []) {
            @Override
            List<ReadyTask> listReady(int limit) {
                super.listReady(limit)
                throw new RuntimeException('tracker unreachable')
            }
        }

        when:
        def html = render(tracker)

        then: 'the two board-fed blocks say unavailable, with the failure'
        ['attention', 'in-progress'].every {
            block(html, it).contains('Board unavailable: tracker unreachable')
        }

        and: 'the status card and the ledger blocks render normally'
        block(html, 'status').contains('Daemon running')
        !block(html, 'status').contains('status__alert')
        block(html, 'outcomes').contains('class="bar"')
        block(html, 'tokens').contains('claude')

        and: 'exactly one attempt was made for a one-shot render — no built-in retry loop'
        tracker.listReadyCalls == 1
    }

    // Scenario: observability/dashboard-page / Fresh install renders a page
    def "FR3: a fresh install renders the never-ran card, the ledger empty states and the populated board"() {
        when: 'no snapshot and no ledger exist, and the tracker answers'
        def html = render(populatedTracker())

        then:
        block(html, 'status').contains('Daemon has not run here')
        block(html, 'outcomes').contains('No finished tasks yet')
        block(html, 'tokens').contains('No token usage recorded yet')
        block(html, 'attention').contains('Parked widget')
        block(html, 'in-progress').contains('Ready widget')
    }

    // Scenario: observability/dashboard-page / Unreadable ledger renders the empty states
    def "FR3: a ledger that is #description renders the ledger empty states and nothing else degrades"() {
        given:
        writeSnapshot()
        breakLedger(ledgerFile())

        when:
        def html = render(populatedTracker())

        then: 'the ledger blocks fall back to their empty states'
        block(html, 'outcomes').contains('No finished tasks yet')
        block(html, 'tokens').contains('No token usage recorded yet')

        and: 'the status card and the board-fed blocks render normally'
        block(html, 'status').contains('Daemon running')
        block(html, 'attention').contains('Parked widget')
        block(html, 'in-progress').contains('Ready widget')

        where:
        description | breakLedger
        'malformed before its last line' | { Path f ->
            Files.writeString(f, 'not json at all\n' + DELIVERED_LINE + '\n')
        }
        'not readable as a file' | { Path f ->
            Files.createDirectories(f)
        }
    }

    // F94: scenario text aligned to what this test asserts
    // Scenario: observability/dashboard-page / Hygiene block degrades on its own
    def "NFR-O3: hygiene inputs that are #description degrade the hygiene block alone"() {
        given: 'a snapshot whose sweep vital is #description, a healthy ledger and a reachable tracker'
        def base = BaselineSnapshotFixture.baselineSnapshot(NOW, new LifecycleState.Running())
        def vitals = new VitalsSnapshot(base.vitals().heartbeat(), base.vitals().reaper(), base.vitals().janitor(), sweep)
        writeSnapshot(new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), vitals, base.tracker(), [:]))
        Files.writeString(ledgerFile(), DELIVERED_LINE + '\n')

        when:
        def html = render(populatedTracker())

        then: 'the hygiene block shows what it has: the not-run footnote, or the last tick it saw'
        block(html, 'hygiene').contains(hygieneText)

        and: "the rest of the page renders normally; the status card's only line is the hygiene's own alarm, if any"
        block(html, 'status').contains('Daemon running')
        (block(html, 'status') =~ /<div class="status__alert">([^<]*)</).collect {
            it[1]
        } == alarms
        block(html, 'outcomes').contains('class="bar"')
        block(html, 'attention').contains('Parked widget')
        block(html, 'in-progress').contains('Ready widget')

        where:
        description | sweep | hygieneText | alarms
        'missing' | null | 'Sandbox sweep has not run yet' | []
        'stale' | new SweepVital(NOW.minusSeconds(3600), 300L, SweepCounts.NONE, [], 0, 0, null) | 'Sandbox hygiene' | ['sandbox sweep not running']
    }
}
