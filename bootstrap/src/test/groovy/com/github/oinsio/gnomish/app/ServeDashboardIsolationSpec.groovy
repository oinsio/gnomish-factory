package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.status.DaemonComponent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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
 * The bulkhead between the embedded dashboard and the daemon (design D11, D12 of
 * supervise-daemon-loops-and-embed-dashboard), proven on a real {@code gnomish serve --dashboard
 * --drain} pass assembled through the production owners — a real git project with an {@code
 * origin}, a real {@link InMemoryTracker} and the fake agent binary. The board's reader is the
 * separate adapter instance {@link BoardReaders#boardReader} builds ({@link
 * BoardReaderSplitFactory} tells it apart by the reader's empty tenure record), so each feature
 * breaks only the page and asserts the daemon did not notice: a board outage never reaches the
 * snapshot's tracker health, and a page disabled by repeated deaths leaves the daemon claiming and
 * delivering.
 *
 * <p>In each feature the daemon's first tracker read waits until the page's failure has happened,
 * so the claim and the delivery provably come after it.
 *
 * <p>Implements FR10, NFR-R1 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(120)
class ServeDashboardIsolationSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String INSTANCE_NAME = 'factory-01' // FakeAgentSupport.propertiesFor's instance name
    private static final long GATE_SECONDS = 30

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir, '100ms')
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        homeDir = tempDir.resolve('home')
    }

    private Path serveDir() {
        homeDir.resolve("projects/${RegisteredCloneFixture.PROJECT}/serve/${INSTANCE_NAME}")
    }

    /** The daemon's tracker, gated on {@code pageBroke} and seeded with one Ready task. */
    private static GatedDaemonTracker gatedDaemonTracker(Closure<Boolean> pageBroke) {
        def tracker = new GatedDaemonTracker(pageBroke)
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please add widgets')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        tracker
    }

    private void drain(FactoryProperties properties, ManualRunAssembly assembly, TrackerAdapterFactory factory) {
        newDrainCommand(properties, assembly, RegisteredCloneFixture.unregistered(homeDir, projectDir), factory)
                .run(args('serve', "--dir=$projectDir", '--drain', '--dashboard'))
    }

    // FR10, NFR-R1 (factory-serve "Board outage is not a daemon tracker failure"), D11.
    def "NFR-R1: board reads failing for the whole run leave the daemon's tracker health clean while it delivers"() {
        given: 'a board reader whose every read fails'
        def boardFailures = new AtomicInteger()
        def boardFailed = new CountDownLatch(1)
        def boardReader = new InMemoryTracker() {
                    @Override
                    List<ReadyTask> listReady(int limit) {
                        boardFailures.incrementAndGet()
                        boardFailed.countDown()
                        throw new IllegalStateException('board outage')
                    }

                    @Override
                    List<OpenTask> listOpen() {
                        boardFailures.incrementAndGet()
                        throw new IllegalStateException('board outage')
                    }
                }

        and: 'a daemon tracker that only starts reading once the board has failed'
        def daemonTracker = gatedDaemonTracker({
            boardFailed.await(GATE_SECONDS, TimeUnit.SECONDS)
        })
        def properties = FakeAgentSupport.propertiesFor('plain-round')

        when:
        drain(properties, newAssembly(properties), new BoardReaderSplitFactory(daemonTracker, boardReader))

        then: 'the page read its own client, and that client failed before the daemon read anything'
        daemonTracker.gateOpened == [true]
        boardFailures.get() >= 1

        and: 'the daemon claimed and delivered the task'
        daemonTracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: "the snapshot's tracker section saw only the daemon's own successful calls"
        def health = readJson(ObservabilityPaths.snapshotFile(serveDir())).get('tracker')
        health.get('consecutiveFailures').asInt() == 0
        !health.get('lastSuccessAt').isNull()
    }

    // FR10, NFR-R1, UX4 (factory-serve "Disabled dashboard, working daemon"), D7: Bounded 5 in 10 min.
    def "NFR-R1, UX4: a dashboard disabled by tick Errors leaves the daemon claiming and completing tasks"() {
        given: 'a board reader whose reads throw an Error, which the loop\'s guard lets through, so each kills the worker'
        def boardReads = new AtomicInteger()
        def boardReader = new InMemoryTracker() {
                    @Override
                    List<ReadyTask> listReady(int limit) {
                        if (boardReads.incrementAndGet() <= 6) {
                            throw new Error('board read died')
                        }
                        throw new IllegalStateException('board outage')
                    }
                }

        and: 'virtual time that only the dashboard moves, so its deaths and backoffs pass at once'
        def clock = new VirtualClock(Instant.parse('2026-10-09T00:00:00Z'))
        def sleeper = new DashboardOnlySleeper(clock, clock.instant().plus(Duration.ofHours(1)))
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        def assembly = newAssembly(properties, VirtualTimeEquipment.on(clock, sleeper))

        and: 'a daemon tracker that only starts reading once the page has been disabled'
        def loopLogs = LogCaptureSupport.attach(SupervisedLoop, Level.INFO)
        def daemonTracker = gatedDaemonTracker({
            awaitEvent(loopLogs, OperatorEvent.DAEMON_LOOP_GAVE_UP)
        })

        when:
        drain(properties, assembly, new BoardReaderSplitFactory(daemonTracker, boardReader))

        then: 'the page was disabled, with one ERROR framed as the dashboard, before the daemon read anything'
        daemonTracker.gateOpened == [true]
        def disabled = events(loopLogs, OperatorEvent.DAEMON_LOOP_GAVE_UP)
        disabled.size() == 1
        disabled[0].level == Level.ERROR
        disabled[0].MDCPropertyMap['component'] == DaemonComponent.DASHBOARD.key()
        events(loopLogs, OperatorEvent.DAEMON_LOOP_WORKER_DIED).size() == 5

        and: 'the daemon then claimed and delivered the task'
        daemonTracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished
        readJson(ObservabilityPaths.snapshotFile(serveDir())).get('lifecycle').get('state').asText() == 'stopped'

        cleanup:
        loopLogs.detach()
    }

    private static List events(LogCaptureSupport logs, OperatorEvent event) {
        (logs.list.toArray() as List).findAll {
            it != null && it.formattedMessage.startsWith(event.head())
        }
    }

    /** Polls the capture, bounded, for {@code event}; true once it is there. */
    private static boolean awaitEvent(LogCaptureSupport logs, OperatorEvent event) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_SECONDS)
        while (events(logs, event).isEmpty() && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        !events(logs, event).isEmpty()
    }
}
