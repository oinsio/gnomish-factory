package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.status.DaemonComponent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The dashboard's watch loop as a supervised daemon loop (design D7, D10 of
 * supervise-daemon-loops-and-embed-dashboard): {@link DashboardWatch} hands its tick to a Bounded
 * loop framed as {@code component=dashboard}. A tracker outage is a degraded section, never a death
 * (dashboard-page "Tracker outage is not a death"); a thread that keeps dying is disabled after five
 * restarts within ten minutes, and {@link DashboardWatch#awaitEnd()} says so. All on virtual time:
 * the scripted sleepers move the {@link VirtualClock} instead of blocking.
 *
 * <p>Implements FR9, FR14 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(10)
class DashboardWatchSupervisionSpec extends Specification {

    private static final long BOUND_MS = 5000

    @TempDir
    Path homeDir

    def clock = new VirtualClock(Instant.parse('2026-08-06T00:00:00Z'))
    def loopLogs = LogCaptureSupport.attach(SupervisedLoop, Level.DEBUG)
    DashboardWatch watch

    def cleanup() {
        watch?.stopAndRenderFinal()
        loopLogs.detach()
    }

    private DashboardWatch newWatch(RecordingReadOnlyTracker tracker, Sleeper sleeper) {
        watch = new DashboardWatch(FactoryHome.at(homeDir).project(new ProjectName('widgets')), 'nightly',
                homeDir.resolve('dashboard.html'),
                new BoardSource(tracker, new TrackerConfig('github', 3), new FactoryProperties.Tracker(null, null)),
                VirtualTimeEquipment.on(clock, sleeper))
    }

    private List events(OperatorEvent event) {
        (loopLogs.list.toArray() as List).findAll {
            it != null && it.formattedMessage.startsWith(event.head())
        }
    }

    // FR9 (dashboard-page "Tracker outage is not a death").
    def "FR9: an hour of tracker outage keeps the loop rendering on the cached board and never disables it"() {
        given: 'a tracker that answers once, then is unreachable'
        def tracker = new RecordingReadOnlyTracker([], []) {
            @Override
            List<ReadyTask> listReady(int limit) {
                super.listReady(limit)
                if (listReadyCalls> 1) {
                    throw new RuntimeException('tracker down')
                }
                []
            }
        }

        and: 'a sleeper that lets one virtual hour of waits pass, then parks until the stop cuts it short'
        def waits = []
        def hourPassed = new CountDownLatch(1)
        int hourOfWaits = (int) (Duration.ofHours(1).seconds / DashboardWatch.RENDER_CADENCE.seconds)
        def sleeper = { Duration d ->
            if (waits.size() <hourOfWaits) {
                waits << d
                clock.advance(d)
                return
            }
            hourPassed.countDown()
            try {
                new CountDownLatch(1).await()
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        } as Sleeper
        newWatch(tracker, sleeper)

        when: 'the loop runs through the hour, and the page is removed just before the final render'
        watch.start()
        assert hourPassed.await(BOUND_MS, TimeUnit.MILLISECONDS)
        Files.delete(homeDir.resolve('dashboard.html'))
        watch.stopAndRenderFinal()

        then: 'it rendered every cycle of the hour, re-trying the board once per board cadence'
        waits.size() == hourOfWaits
        waits.every { it == DashboardWatch.RENDER_CADENCE }
        tracker.listReadyCalls == 1 + (int) (Duration.ofHours(1).seconds / DashboardWatch.BOARD_CADENCE.seconds)

        and: 'no failure, death or give-up was ever reported, and the loop ended by the stop'
        events(OperatorEvent.DAEMON_LOOP_TICK_FAILED).isEmpty()
        events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).isEmpty()
        events(OperatorEvent.DAEMON_LOOP_GAVE_UP).isEmpty()
        !watch.awaitEnd()

        and: 'the stop rendered the page once more, synchronously'
        Files.readString(homeDir.resolve('dashboard.html')).startsWith('<!doctype html>')
    }

    // FR9 (dashboard-page "A watch render loop that keeps dying is disabled"), D7: Bounded 10 s / 10 min / 5 in 10 min.
    def "FR9: a watch thread that keeps dying is disabled on the sixth death, and awaitEnd says it gave up"() {
        given: 'every render-cadence wait kills the thread; every other sleep is a restart backoff'
        def calls = new AtomicInteger()
        def backoffs = Collections.synchronizedList([])
        def sleeper = { Duration d ->
            if (calls.incrementAndGet() % 2 == 1) {
                throw new Error('cadence wait killed the watch thread')
            }
            backoffs << d
            clock.advance(d)
        } as Sleeper
        newWatch(new RecordingReadOnlyTracker([], []), sleeper)

        when:
        watch.start()
        boolean gaveUp = watch.awaitEnd()

        then: 'five respawns, each after the base backoff (every respawned tick was clean), then the give-up'
        gaveUp
        backoffs == [DashboardWatch.RENDER_CADENCE] * 5

        and: 'one ERROR, framed as the dashboard'
        def disabled = events(OperatorEvent.DAEMON_LOOP_GAVE_UP)
        disabled.size() == 1
        disabled[0].level == Level.ERROR
        disabled[0].MDCPropertyMap['component'] == DaemonComponent.DASHBOARD.key()
        disabled[0].argumentArray[1..3] == [5, 5, Duration.ofMinutes(10)]
    }

    // FR11, UX2 (factory-serve "Page after Ctrl-C", the second-pass scenario), D9: the drain body and
    //     the shutdown hook may both reach the final render; the first one is the page's last word.
    def "FR11: a second stopAndRenderFinal leaves the final page byte-identical, even minutes later"() {
        given:
        newWatch(new RecordingReadOnlyTracker([], []), { Duration d ->
            clock.advance(d)
        } as Sleeper)
        def page = homeDir.resolve('dashboard.html')

        when: 'the final render, then a second pass two minutes of render time later'
        watch.stopAndRenderFinal()
        def finalPage = Files.readAllBytes(page)
        clock.advance(Duration.ofMinutes(2))
        watch.stopAndRenderFinal()

        then:
        Files.readAllBytes(page) == finalPage
    }
}
