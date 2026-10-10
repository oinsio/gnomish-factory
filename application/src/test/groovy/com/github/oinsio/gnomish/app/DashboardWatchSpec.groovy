package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, FR14 of supervise-daemon-loops-and-embed-dashboard (design D10): the watch cycle of {@link
 * DashboardWatch} (its output path and one-shot render are {@code DashboardWatchAssemblySpec}'s). The cycle — the loop's tick, driven
 * here directly over a {@link VirtualClock} the spec advances — re-renders on the render cadence and
 * refreshes the board only on its own slower cadence, keeps writing through a board outage, and
 * swallows an output-write failure (FR7, FR8, FR9, NFR-P1, NFR-R1, NFR-R2, M2 of add-dashboard-page).
 * The loop around the tick is {@code DashboardWatchSupervisionSpec}'s subject.
 */
class DashboardWatchSpec extends Specification {

    private static final Instant T0 = Instant.parse('2026-08-06T00:00:00Z')

    @TempDir
    Path homeDir

    def clock = new VirtualClock(T0)
    def tracker = new RecordingReadOnlyTracker([], [])
    def layout = FactoryHome.at(homeDir).project(new ProjectName('widgets'))

    private DashboardWatch newWatch(Path out = homeDir.resolve('dashboard.html'), RecordingReadOnlyTracker source = tracker) {
        new DashboardWatch(layout, 'nightly', out,
                new BoardSource(source, new TrackerConfig('github', 3), new FactoryProperties.Tracker(null, null)),
                VirtualTimeEquipment.on(clock, Stub(Sleeper)))
    }

    private static RecordingReadOnlyTracker failingFirst(int failures) {
        new RecordingReadOnlyTracker([], []) {
            @Override
            List<ReadyTask> listReady(int limit) {
                super.listReady(limit)
                if (listReadyCalls <= failures) {
                    throw new RuntimeException('tracker down')
                }
                []
            }
        }
    }


    def "a render cycle between board refreshes reuses the cached model without refetching"() {
        given: 'two cycles inside the 60s board cadence'
        def watch = newWatch()

        when:
        watch.tick()
        clock.advance(Duration.ofSeconds(10))
        watch.tick()

        then:
        tracker.listReadyCalls == 1
    }

    def "a render cycle at or past the board cadence refetches"() {
        given: 'the second cycle lands exactly at the 60s board cadence'
        def watch = newWatch()

        when:
        watch.tick()
        clock.advance(Duration.ofSeconds(60))
        watch.tick()

        then:
        tracker.listReadyCalls == 2
    }

    def "M2: over one hour of render cycles the board is fetched once per board cadence, never per render"() {
        given: 'one full hour of render cycles at the 10s render cadence'
        int cycles = (int) (Duration.ofHours(1).seconds / DashboardWatch.RENDER_CADENCE.seconds)
        def watch = newWatch()

        when: 'every render cycle in the hour runs, one render cadence apart'
        cycles.times {
            watch.tick()
            clock.advance(DashboardWatch.RENDER_CADENCE)
        }

        then: 'tracker reads stay within the board-cadence budget: one fetch per board interval, not per render'
        tracker.listReadyCalls == (int) (Duration.ofHours(1).seconds / DashboardWatch.BOARD_CADENCE.seconds)
    }

    // FR15 of harden-logging-observability: the page silently stops updating unless the swallow
    // leaves a coded WARN naming the file it could not write.
    def "NFR-R1: an output-write failure is logged and swallowed so the watch loop keeps running"() {
        given: 'an output path whose parent cannot be created — a regular file sits where the directory must be'
        def blocker = Files.createFile(homeDir.resolve('blocker'))
        def unwritable = blocker.resolve('dashboard.html')
        def watch = newWatch(unwritable)
        def logs = LogCaptureSupport.attach(DashboardWatch)

        when: 'a cycle renders but the atomic write cannot place its file'
        watch.tick()

        then: 'the write failure never propagates — the loop survives to render the next cycle'
        noExceptionThrown()
        !Files.exists(unwritable)

        and: 'and it is not silent: one coded WARN naming the unwritable target'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.DASHBOARD_RENDER_WRITE_FAILED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains(unwritable.toString())

        cleanup:
        logs.detach()
    }

    def "each cycle atomically replaces the output file with a complete, self-contained page"() {
        when:
        newWatch().tick()

        then:
        def html = Files.readString(homeDir.resolve('dashboard.html'))
        html.startsWith('<!doctype html>')
        html.contains('</html>')
    }

    def "a watch-mode cycle marks the page as watch mode and bakes its meta-refresh"() {
        when:
        newWatch().tick()

        then: 'the mode the static script reads to arm its stale degradation (FR3, FR10 of redesign-dashboard)'
        def html = Files.readString(homeDir.resolve('dashboard.html'))
        html.contains('data-mode="watch"')
        html.contains('<meta http-equiv="refresh" content="10">')

        and: 'the freshness strip replaced the full-viewport banner entirely'
        !html.contains('staleness-banner')
        html.contains('id="freshness"')
    }

    def "a board fetch failure degrades the board section but the cycle still writes a complete page"() {
        when:
        newWatch(homeDir.resolve('dashboard.html'), failingFirst(1)).tick()

        then:
        def html = Files.readString(homeDir.resolve('dashboard.html'))
        html.contains('unavailable')
        html.contains('tracker down')
    }

    def "the loop keeps running across a board outage: the next cycle after recovery refreshes normally"() {
        given: 'the fetch fails on the first (due) cycle and succeeds on the second, past the board cadence'
        def flaky = failingFirst(1)
        def watch = newWatch(homeDir.resolve('dashboard.html'), flaky)

        when:
        watch.tick()
        clock.advance(Duration.ofSeconds(60))
        watch.tick()

        then:
        flaky.listReadyCalls == 2
        !Files.readString(homeDir.resolve('dashboard.html')).contains('unavailable')
    }
}
