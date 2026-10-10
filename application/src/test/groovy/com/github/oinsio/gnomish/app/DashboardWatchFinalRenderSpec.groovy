package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The final render of {@link DashboardWatch#stopAndRenderFinal()} (design D9 of
 * supervise-daemon-loops-and-embed-dashboard): it runs on the caller's thread — {@code serve}'s
 * drain body or its JVM shutdown hook — outside the supervised loop's guard, so it must neither
 * reach the tracker nor let any failure escape into the teardown that follows it (NFR-R1).
 *
 * <p>Implements FR11, NFR-R1 of supervise-daemon-loops-and-embed-dashboard.
 */
class DashboardWatchFinalRenderSpec extends Specification {

    @TempDir
    Path homeDir

    def clock = new VirtualClock(Instant.parse('2026-08-06T00:00:00Z'))
    def tracker = new RecordingReadOnlyTracker([], [])
    def layout = FactoryHome.at(homeDir).project(new ProjectName('widgets'))
    def page = homeDir.resolve('dashboard.html')

    private DashboardWatch newWatch() {
        new DashboardWatch(layout, 'nightly', page,
                new BoardSource(tracker, new TrackerConfig('github', 3), new FactoryProperties.Tracker(null, null)),
                VirtualTimeEquipment.on(clock, Stub(Sleeper)))
    }

    def "NFR-R1: the final render reuses the cached board and never calls the tracker, however stale the cache"() {
        given: 'one watch cycle fetched the board, and the stop arrives long past the board cadence'
        def watch = newWatch()
        watch.tick()
        clock.advance(Duration.ofMinutes(5))

        when:
        watch.stopAndRenderFinal()

        then: 'no network call inside the teardown: the board is the one the last cycle fetched'
        tracker.listReadyCalls == 1

        and: 'the page was still rendered once more, at the stop instant'
        Files.readString(page).startsWith('<!doctype html>')
    }

    def "NFR-R1: a final render that throws is swallowed, so the teardown after it still runs"() {
        given: 'a snapshot the render cannot parse past its JSON layer — no writtenAt instant'
        def watch = newWatch()
        def snapshot = ObservabilityPaths.snapshotFile(layout.serveDir('nightly'))
        Files.createDirectories(snapshot.parent)
        Files.writeString(snapshot, '{}')

        and:
        def logs = LogCaptureSupport.attach(DashboardWatch)

        when:
        watch.stopAndRenderFinal()

        then: 'nothing reaches the drain body or the hook, which go on to the logging stop'
        noExceptionThrown()

        and: 'and it is not silent: one coded WARN carrying the cause'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.DASHBOARD_FINAL_RENDER_FAILED.head())
        }
        event != null
        event.level == Level.WARN
        event.throwableProxy.className == NullPointerException.name

        cleanup:
        logs.detach()
    }
}
