package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR9 of supervise-daemon-loops-and-embed-dashboard ("a standalone {@code gnomish dashboard}
 * SHALL keep working unchanged"): a daemon that runs without the embedded dashboard leaves only
 * its snapshot behind, and an operator's own {@code gnomish dashboard --watch} — another process,
 * here the real command — renders that daemon's page from it in watch mode, at the path the
 * operator chose, as it did before the daemon could render one itself.
 */
class StandaloneDashboardBesideDaemonSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE = 'nightly'

    @TempDir
    Path tempDir

    // Scenario: serve/factory-serve / Standalone dashboard unaffected
    @Timeout(10)
    def "FR9: with the daemon's dashboard off, a standalone --watch renders the daemon's page from its snapshot"() {
        given: 'a daemon without the dashboard: its serve directory holds a snapshot and no page'
        def projectDir = GnomishProjectFixture.writeGnomishProject(tempDir.resolve('project'))
        def factoryHome = tempDir.resolve('gnomish-home')
        def serveDir = FactoryHome.at(factoryHome).project(new ProjectName(RegisteredCloneFixture.PROJECT)).serveDir(INSTANCE)
        def snapshot = ObservabilityPaths.snapshotFile(serveDir)
        Files.createDirectories(snapshot.parent)
        Files.writeString(snapshot, getClass().getResourceAsStream('/snapshot-v1.reference.json').getText('UTF-8'))
        def daemonFiles = Files.list(serveDir).withCloseable { it.toList() }

        and: 'a standalone watch whose loop renders, then parks in its render-cadence wait'
        def waiting = new CountDownLatch(1)
        def sleeper = { Duration d ->
            waiting.countDown()
            new CountDownLatch(1).await()
        } as Sleeper
        def command = DashboardCommandFixture.command(factoryHome, projectDir, new RecordingReadOnlyTracker([], []), sleeper, INSTANCE)
        def out = tempDir.resolve('operator.html')
        def runner = Thread.ofPlatform().start {
            try {
                command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString(), '--watch'))
            } catch (Throwable ignored) {
                // the join is interrupted below; the page is what is asserted
            }
        }

        when: 'the watch has rendered once'
        waiting.await(5, TimeUnit.SECONDS)
        runner.interrupt()
        runner.join()

        then: 'the page is at the operator\'s path, in watch mode, showing the daemon\'s instance'
        def page = Files.readString(out)
        page.contains('data-mode="watch"')
        page.contains('gnomish-factory-x7k2q1')

        and: 'the daemon\'s own directory is as the daemon left it: no page appeared there'
        Files.list(serveDir).withCloseable { it.toList() } == daemonFiles
    }
}
