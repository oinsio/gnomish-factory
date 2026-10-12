package com.github.oinsio.gnomish.e2e.dashboard

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.BoardSource
import com.github.oinsio.gnomish.app.DashboardWatch
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectLayout
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.TaskOutcome
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLine
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Writes the dashboard pages the browser specs load, through the production owners only (testing.md,
 * "Fixtures assemble through production owners"): the page file is written by {@link DashboardWatch}
 * — the one dashboard assembly, which owns the output path, the render cycle and the board fetch —
 * from a real {@link InMemoryTracker} and a ledger appended through the same {@link
 * RotatingLedgerAppender} over a {@link LedgerAppender} that {@code ObservabilityAssembly} builds. No
 * HTML is written here: every byte the browser sees is the renderer's.
 *
 * <p>The page carries every block populated — ready, working and parked tasks with titles long enough
 * to test wrapping, a week of outcomes covering all four segments, and token usage covering all four
 * token segments — so the layout and colour specs see each element the stylesheet styles.
 *
 * <p>Time is the spec's: the watch is built on a {@link VirtualClock}, so the page's {@code
 * generatedAt} is {@link #GENERATED_AT}, the instant the browser specs set the page clock against.
 *
 * <p>Implements FR14 of tighten-spec-contract (design D15).
 */
final class DashboardPages {

    /** The page's observation instant; the browser specs set the page clock relative to it. */
    static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:30:00Z')

    private static final String INSTANCE = 'default'
    private static final int OUTCOME_DAYS = 6

    private DashboardPages() {}

    /**
     * Renders a {@code --watch} page — meta-refresh, stale threshold and the watch strip — into
     * {@code home}, the way {@code serve --dashboard} and {@code gnomish dashboard --watch} do: one
     * watch cycle fetches the board and writes the page, and the stop renders it once more.
     */
    static Path watchPage(Path home) {
        def clock = new VirtualClock(GENERATED_AT)
        def layout = seedLedger(home, clock)
        def firstWait = new CountDownLatch(1)
        def parkingSleeper = { Duration ignored ->
            firstWait.countDown()
            try {
                new CountDownLatch(1).await()
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt()
            }
        } as Sleeper
        def watch = newWatch(layout, VirtualTimeEquipment.on(clock, parkingSleeper))
        watch.start()
        assert firstWait.await(30, TimeUnit.SECONDS): 'the watch loop never reached its first wait'
        watch.stopAndRenderFinal()
        watch.outputFile()
    }

    private static DashboardWatch newWatch(ProjectLayout layout, TimeEquipment time) {
        new DashboardWatch(layout, INSTANCE, null,
                new BoardSource(seededTracker(), new TrackerConfig('github', 3), new FactoryProperties.Tracker(null, null)),
                time)
    }

    private static InMemoryTracker seededTracker() {
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        seed(harness, 'PROJ-11', 'Teach the release workflow to attest the reproducible archives', new TrackerTaskState.Ready())
        seed(harness, 'PROJ-12', 'Split-the-extraordinarily-long-configuration-loader-module-name-without-any-break', new TrackerTaskState.Ready())
        seed(harness, 'PROJ-13', 'Document the operator guide for the remote outage gate', new TrackerTaskState.Ready())
        seed(harness, 'PROJ-21', 'Move the sandbox egress guard onto the supervised daemon loop', new TrackerTaskState.Ready())
        seed(harness, 'PROJ-22', 'Retire-the-one-time-scenarios-of-the-module-layering-capability-in-one-word', new TrackerTaskState.Ready())
        seed(harness, 'PROJ-31', 'Decide whether the checkpoint gate may skip the judge stage on a resumed run',
                new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION))
        seed(harness, 'PROJ-32', 'Approve-the-manual-checkpoint-of-the-dashboard-browser-layer-before-delivery',
                new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT))
        tracker.claim(new TaskRef('PROJ-21'), 'factory-01')
        tracker.claim(new TaskRef('PROJ-22'), 'factory-02')
        tracker
    }

    private static void seed(InMemoryTrackerHarness harness, String id, String title, TrackerTaskState state) {
        harness.seed(new TaskRef(id),
                new TaskSnapshot(id, UntrustedText.tracker(title), UntrustedText.tracker('fixture body')),
                state, AbortFacts.none())
    }

    /**
     * Appends a week of task outcomes, one ledger file per day, through the production appender;
     * leaves {@code clock} at {@link #GENERATED_AT}.
     */
    private static ProjectLayout seedLedger(Path home, VirtualClock clock) {
        def layout = FactoryHome.at(home).project(new ProjectName('widgets'))
        def serveDir = layout.serveDir(INSTANCE)
        def firstDay = GENERATED_AT.minus(Duration.ofDays(OUTCOME_DAYS - 1))
        def dayClock = new VirtualClock(firstDay)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(ObservabilityPaths.ledgerFile(serveDir, LocalDate.ofInstant(firstDay, ZoneOffset.UTC)),
                new LedgerJsonMapper()),
                serveDir, dayClock)
        def instance = new InstanceInfo('factory-01', 'build-host', '0.0.0-test')
        def outcomes = TaskOutcome.values()
        OUTCOME_DAYS.times { int day ->
            (3 + day).times { int n ->
                def outcome = outcomes[(day + n) % outcomes.length]
                def finished = dayClock.instant()
                appender.append(new TaskOutcomeLine(instance, "PROJ-${100 + 10 * day + n}", outcome,
                        outcome == TaskOutcome.AWAITING_HUMAN ? ParkReason.ESCALATION : null,
                        'implement', 1, finished.minus(Duration.ofMinutes(20)), finished, 1_200_000L,
                        ['claude-sonnet': new LedgerTokenUsage(42_000L, 9_000L, 15_000L, 180_000L)]))
            }
            dayClock.advance(Duration.ofDays(1))
        }
        assert clock.instant() == GENERATED_AT
        layout
    }
}
