package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
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
 * FR9, FR10, NFR-S1 of supervise-daemon-loops-and-embed-dashboard (design D11, D12): {@link
 * ServeDashboard} builds the page inside {@code serve} only when the effective switch is on, over a
 * board reader {@link BoardReaders} builds from what {@code serve} bound — under a freshly minted
 * reader id, never the daemon's — and with the bound configuration, through the one {@link
 * DashboardWatch} that owns the page's path.
 */
class ServeDashboardSpec extends Specification implements RunChainFakes {

    private static final String INSTANCE_NAME = 'nightly'

    @TempDir
    Path tempDir

    RegisteredClone clone
    BoardReaders boardReaders = Mock()
    def reader = new RecordingReadOnlyTracker([], [])
    def boundConfig = new TrackerConfig('github', 3, TrackerConfig.DEFAULT_HEARTBEAT_INTERVAL,
    TrackerConfig.DEFAULT_HEARTBEAT_TTL_MULTIPLIER, 7, [:])
    def bound = new BoundTracker(pipeline(), DEFAULT_TRUSTED_BASE, boundConfig, Stub(TrackerAdapterFactory),
    Stub(Tracker), INSTANCE)

    def setup() {
        def clonePath = Files.createDirectories(tempDir.resolve('widgets'))
        clone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), clonePath)
    }

    private ServeDashboard serveDashboard() {
        new ServeDashboard(boardReaders, RegisteredCloneFixture.scope(clone, INSTANCE_NAME),
                testProperties(instanceName: INSTANCE_NAME),
                VirtualTimeEquipment.on(new VirtualClock(Instant.parse('2026-10-09T00:00:00Z')), Stub(Sleeper)))
    }

    private ServeArguments arguments(boolean dashboard, Path out = null) {
        new ServeArguments(clone.clonePath(), null, false, dashboard, out)
    }

    // FR9, D12: with the switch off there is no page, and no board client is ever built for one.
    def "FR9: with the dashboard off no watch is built and no board reader is asked for"() {
        when:
        def watch = serveDashboard().forRun(arguments(false), bound)

        then:
        watch.isEmpty()
        0 * boardReaders._
    }

    // FR9, FR10, D11: one reader from the bound tracker, under its own minted id that names the
    //     project and instance — never the daemon's claiming identity.
    def "FR10: with the dashboard on the reader is built from the bound tracker under a fresh reader id"() {
        given:
        InstanceId readerId = null

        when:
        def watch = serveDashboard().forRun(arguments(true), bound)

        then:
        1 * boardReaders.boardReader({
            it.is(bound)
        }, _) >> { BoundTracker b, InstanceId id ->
            readerId = id
            reader
        }
        watch.isPresent()
        readerId != INSTANCE
        readerId.value().startsWith("${RegisteredCloneFixture.PROJECT}-$INSTANCE_NAME-")
    }

    // FR9, D10: the page path is DashboardWatch's own default — dashboard.html in the instance's
    //     serve directory — unless --dashboard-out overrides it.
    def "FR9: the page goes to the instance's serve directory, or to the --dashboard-out override"() {
        given:
        boardReaders.boardReader(_, _) >> reader

        expect:
        serveDashboard().forRun(arguments(true), bound).get().outputFile() ==
                clone.layout().serveDir(INSTANCE_NAME).resolve('dashboard.html')
        serveDashboard().forRun(arguments(true, tempDir.resolve('wall.html')), bound).get().outputFile() ==
                tempDir.resolve('wall.html')
    }

    // FR10, NFR-S1, D11: the page reads the board through the built reader and judges it by the
    //     configuration serve bound — its WIP denominator is the bound limit.
    def "FR10: the page reads the board through the reader and shows the bound WIP limit"() {
        given:
        boardReaders.boardReader(_, _) >> reader
        def out = tempDir.resolve('page.html')

        when:
        serveDashboard().forRun(arguments(true, out), bound).get().renderOnce()

        then:
        reader.listReadyCalls == 1
        Files.readString(out).contains('>0 / 7</div>')
    }
}
