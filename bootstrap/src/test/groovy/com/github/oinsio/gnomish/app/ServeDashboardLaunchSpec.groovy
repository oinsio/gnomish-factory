package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.InterruptOnlySleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * FR9, UX1, NFR-O2 of supervise-daemon-loops-and-embed-dashboard (design D12): a {@code serve}
 * started with the dashboard on names the page's absolute path on the operator console before any
 * claim, records the switch and the path in the start anchor, and its page appears; with the
 * dashboard off, nothing is printed and the anchor says so.
 *
 * <p>The command is assembled through the production owners ({@link ServeCommands}) on a virtual
 * clock whose sleeper only returns on interrupt: the daemon's loops tick once and then wait for
 * good, so no render outlives the feature on a deleted folder. The page's final render on shutdown
 * is task 9.5's subject, not this spec's.
 */
class ServeDashboardLaunchSpec extends ServeCommandSpecBase {

    ScriptedConsoleIO console = new ScriptedConsoleIO()

    private ServeCommand dashboardCommand() {
        def time = VirtualTimeEquipment.on(new VirtualClock(Instant.parse('2026-10-09T00:00:00Z')),
                new InterruptOnlySleeper())
        def properties = testProperties(instanceName: INSTANCE_NAME)
        ServeCommands.of(newAssembly(properties, time), TaskGitFixture.real(), registeredClone, 'taskId', properties,
                new ServeProperties(0, null, null, null, null, null, null, null, null, null),
                new TrackerWiring([github: fakeFactory(tracker)], MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()),
                new CapturingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(), console)
    }

    private static String startAnchor(LogCaptureSupport capture) {
        def starts = capture.list.findAll {
            it.formattedMessage.startsWith('serve started:')
        }
        assert starts.size() == 1
        starts[0].formattedMessage
    }

    // UX1, NFR-O2, FR9: the default page is dashboard.html in the instance's serve directory; the
    //     operator reads its absolute path on the console, and the anchor records the same path.
    def "UX1: serve --dashboard prints the page's absolute path and records it in the start anchor"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        tracker.listReady(_) >> []
        tracker.listOpen() >> []
        def page = registeredClone.layout().serveDir(INSTANCE_NAME).resolve('dashboard.html').toAbsolutePath()
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        runsToCompletion {
            dashboardCommand().run(args('serve', "--dir=$projectDir", '--dashboard'))
        }

        then: 'one line names the page, on the human path'
        console.printed == [
            "gnomish serve: dashboard -> $page" + ConsoleIO.LINE_END
        ]
        console.printedMachine.isEmpty()

        and: 'the start anchor records the switch and the same path'
        startAnchor(capture).endsWith(", dashboard=true, dashboardOut=$page")

        and: 'the page the line names appears'
        pageAppears(page)

        cleanup:
        capture.detach()
    }

    // UX1, FR9: --dashboard-out overrides the page path; the line and the anchor name the override.
    def "UX1: serve --dashboard-out names the overriding page in the line and the anchor"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        tracker.listReady(_) >> []
        tracker.listOpen() >> []
        def page = tempDir.resolve('wall/dashboard.html').toAbsolutePath()
        Files.createDirectories(page.parent)
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        runsToCompletion {
            dashboardCommand().run(args('serve', "--dir=$projectDir", '--dashboard', "--dashboard-out=$page"))
        }

        then:
        console.printed == [
            "gnomish serve: dashboard -> $page" + ConsoleIO.LINE_END
        ]
        startAnchor(capture).endsWith(", dashboard=true, dashboardOut=$page")
        pageAppears(page)

        cleanup:
        capture.detach()
    }

    // NFR-O2, FR9: off by default — no line, no page, and the anchor says the dashboard is off.
    def "NFR-O2: without --dashboard nothing is printed and the anchor records the dashboard as off"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        runsToCompletion {
            dashboardCommand().run(args('serve', "--dir=$projectDir"))
        }

        then:
        console.printed.isEmpty()
        startAnchor(capture).endsWith(', dashboard=false, dashboardOut=none')
        !Files.exists(registeredClone.layout().serveDir(INSTANCE_NAME).resolve('dashboard.html'))

        cleanup:
        capture.detach()
    }

    /** The first render runs on the page's own loop thread; waits a bounded while for it to land. */
    private static boolean pageAppears(Path page) {
        def deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!Files.exists(page) && System.nanoTime() <deadline) {
            Thread.sleep(20)
        }
        Files.exists(page)
    }
}
