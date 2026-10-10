package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The embedded page's final render (design D9 of supervise-daemon-loops-and-embed-dashboard), proven
 * on a real {@code gnomish serve --dashboard --drain} pass assembled through the production owners —
 * a real git project with an {@code origin}, a real {@link InMemoryTracker} and the fake agent
 * binary: once the drain has written its {@code stopped} snapshot, the page is rendered from it, so
 * the file an operator has open says the daemon stopped and why (factory-serve "Page after
 * Ctrl-C"). That the shutdown hook's later pass leaves this page unchanged is pinned in {@link
 * ServeShutdownWiringSpec}, which can drive the hook the JVM would run here only at exit.
 *
 * <p>Implements FR11, UX2 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(120)
class ServeDashboardFinalRenderSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String INSTANCE_NAME = 'factory-01' // FakeAgentSupport.propertiesFor's instance name

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir
    InMemoryTracker tracker = new InMemoryTracker()

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

    // FR11, UX2 (factory-serve "Page after Ctrl-C"): the page's last render reads the stopped snapshot.
    def "FR11: serve --dashboard --drain leaves a page that shows the stopped state and its reason"() {
        given: 'one Ready task the fake agent delivers'
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please add widgets')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        def properties = FakeAgentSupport.propertiesFor('plain-round')

        when:
        newDrainCommand(properties, newAssembly(properties),
                RegisteredCloneFixture.unregistered(homeDir, projectDir), fakeFactory(tracker))
                .run(args('serve', "--dir=$projectDir", '--drain', '--dashboard'))

        then: 'the drain really ran and finalized the snapshot as stopped'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished
        readJson(ObservabilityPaths.snapshotFile(serveDir())).get('lifecycle').get('state').asText() == 'stopped'

        and: 'the page names the stopped state and the reason the snapshot records, never a running daemon'
        def page = Files.readString(serveDir().resolve('dashboard.html'))
        page.contains('Daemon stopped (drainComplete)')
        !page.contains('Daemon running')
    }
}
