package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14 of supervise-daemon-loops-and-embed-dashboard (design D10): what {@link DashboardWatch} owns
 * besides its watch cycle — the page's output path (an override, or {@code dashboard.html} in the
 * instance's serve directory), the page's ready window, and the one-shot render through the same
 * assembly (FR1, FR8 of add-dashboard-page). The watch cycle is {@code DashboardWatchSpec}'s
 * subject, the loop around it {@code DashboardWatchSupervisionSpec}'s.
 */
class DashboardWatchAssemblySpec extends Specification {

    @TempDir
    Path homeDir

    def tracker = new RecordingReadOnlyTracker([], [])
    def layout = FactoryHome.at(homeDir).project(new ProjectName('widgets'))

    private DashboardWatch newWatch(Path out = homeDir.resolve('dashboard.html')) {
        new DashboardWatch(layout, 'nightly', out,
                new BoardSource(tracker, new TrackerConfig('github', 3), new FactoryProperties.Tracker(null, null)),
                VirtualTimeEquipment.on(new VirtualClock(Instant.parse('2026-08-06T00:00:00Z')), Stub(Sleeper)))
    }

    // FR14, D10: the default output path is owned here — dashboard.html beside the instance's serve files.
    def "FR14: without an override the page goes to dashboard.html in the instance's serve directory"() {
        expect:
        newWatch(null).outputFile() == layout.serveDir('nightly').resolve('dashboard.html')
    }

    def "FR14: an override replaces the default output path"() {
        given:
        def out = homeDir.resolve('incident.html')

        expect:
        newWatch(out).outputFile() == out
    }

    def "FR14: the board is fetched with the page's own ready window of 50"() {
        when:
        newWatch().tick()

        then:
        tracker.lastLimit == 50
    }

    // FR14, D10: the one-shot render goes through the same assembly — a fresh fetch, no meta-refresh.
    def "FR14: renderOnce fetches the board and writes a one-shot page without the watch mode"() {
        given:
        def watch = newWatch()

        when:
        watch.renderOnce()

        then:
        tracker.listReadyCalls == 1
        def html = Files.readString(homeDir.resolve('dashboard.html'))
        html.startsWith('<!doctype html>')
        !html.contains('data-mode="watch"')
        !html.contains('http-equiv="refresh"')
    }

    def "FR14: a one-shot write failure reaches the caller"() {
        given:
        def blocker = Files.createFile(homeDir.resolve('blocker'))

        when:
        newWatch(blocker.resolve('dashboard.html')).renderOnce()

        then:
        thrown(IOException)
    }
}
