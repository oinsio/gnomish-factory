package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.NOW
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.healthySnapshot
import static com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures.snapshotWithTrackerFailures
import static com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures.WRITTEN_AT
import static com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures.snapshot
import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * Verifies the status card, the page's second priority layer (task 3.2 of
 * redesign-dashboard): the four daemon states with their dot modifiers, the
 * instance id and snapshot {@code writtenAt} as a {@code <time>}, the slots
 * and consecutive-failures stat tiles, and the alarm palette reserved for a
 * failure count that is actually non-zero. The alert lines themselves are
 * {@code DashboardHtmlRendererAlertSpec}'s subject.
 *
 * FR1, FR2, FR8, FR9 of redesign-dashboard.
 */
class DashboardStatusCardSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    @TempDir
    Path tempDir

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    @Unroll
    def "status line: #description"() {
        when:
        def html = renderer.render(view, emptyHistory(), neverFetchedBoard(), noSweepData(), GENERATED_AT, null)

        then:
        html.contains('<div class="status__state">' + expectedState + '</div>')
        html.contains('class="card status' + expectedModifier + '"')

        where:
        description | view | expectedState | expectedModifier
        'no snapshot ever written' | new DaemonSnapshotView.Absent() | 'Daemon has not run here' | ' status--down'
        'a fresh snapshot reads as running' | new DaemonSnapshotView.Fresh(snapshot(new LifecycleState.Running())) | 'Daemon running' | ''
        'a dead daemon reads as not updating' | new DaemonSnapshotView.DeadDaemon(snapshot(new LifecycleState.Running())) | 'Snapshot not updating' | ' status--down'
        'a clean stop is not an alarm' | new DaemonSnapshotView.Stopped(snapshot(new LifecycleState.Stopped('signal'))) | 'Daemon stopped (signal)' | ' status--stopped'
    }

    // FR11 of supervise-daemon-loops-and-embed-dashboard (dashboard-page "Fresh stopped snapshot shows
    //     the stopped state"): the page serve renders right after its final snapshot reads that
    //     snapshot as stopped, with its reason and the stopped dot — and vitals frozen at the stop
    //     raise no alarm, though the same failure count on a running daemon would.
    def "FR11: a fresh stopped snapshot read from disk shows the stopped state, its reason, and no alarm"() {
        given: 'a stopped snapshot written a minute ago, carrying a failure count that alarms a running daemon'
        def base = snapshotWithTrackerFailures()
        def stopped = new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                new LifecycleState.Stopped('drainComplete'), base.feed(), base.slots(), base.vitals(), base.tracker(), [:])
        def file = tempDir.resolve('snapshot.json')
        Files.writeString(file, new SnapshotJsonMapper().serialize(stopped))

        when:
        def view = new SnapshotReader().read(file, NOW)
        def html = renderer.render(view, emptyHistory(), neverFetchedBoard(), noSweepData(), NOW, null)

        then:
        html.contains('<div class="status__state">Daemon stopped (drainComplete)</div>')
        html.contains('class="card status status--stopped"')
        !markup(html).contains('class="status__alert"')
    }

    def "a snapshot carries the instance, its writtenAt as a time element, and both stats"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Fresh(snapshot(new LifecycleState.Running())), emptyHistory(),
                neverFetchedBoard(), noSweepData(), GENERATED_AT, null)

        then:
        html.contains('data-epoch="' + WRITTEN_AT.toEpochMilli() + '">' + WRITTEN_AT + '</time>')
        html.contains('<div class="stat__label">slots</div>')
        html.contains('<div class="stat__label">consecutive failures</div>')

        and: 'a zero failure count is not painted in the alarm palette'
        !markup(html).contains('stat__value--bad')
    }

    def "an absent snapshot says so instead of showing empty stat tiles"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, null)

        then:
        html.contains('no snapshot has been written here')
        !markup(html).contains('stat__label')
    }

    // FR9: the alarm palette on a stat is reserved for a count that is actually wrong —
    //      the tracker-failure tile is the only one that earns it, and only above zero.
    @Unroll
    def "the consecutive-failures tile is painted in the alarm palette only above zero: #description"() {
        when:
        def html = renderer.render(new DaemonSnapshotView.Fresh(view), emptyHistory(), neverFetchedBoard(),
                noSweepData(), NOW, null)

        then:
        markup(html).contains('stat__value--bad') == painted

        and: 'the count itself is rendered either way, with the exact value on hover'
        markup(html).contains('title="' + count + '">' + count + '</div>')

        where:
        description | view | count | painted
        'three failures' | snapshotWithTrackerFailures() | 3 | true
        'no failures' | healthySnapshot() | 0 | false
    }
}
