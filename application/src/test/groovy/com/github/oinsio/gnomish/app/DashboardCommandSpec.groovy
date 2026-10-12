package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR3, NFR-R2 of add-dashboard-page (task 4.1-4.3, design D8): {@link DashboardCommand}
 * resolves the tracker from {@code --dir} exactly like {@link BoardCommand}, defaults its output
 * to {@code dashboard.html} in the instance's observability directory (design D8), honors an
 * explicit {@code --out}, writes atomically, and completes a one-shot render normally — exit zero
 * — even when the tracker is unreachable (FR3's degraded-board case, never an exception).
 *
 * <p>FR10 of add-project-registry: the observability directory is the registered project's
 * {@code GNOMISH_HOME/projects/<name>/serve/<instance>}, computed from the clone the loader resolved.
 */
class DashboardCommandSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE_NAME = 'dashboard-instance'

    @TempDir
    Path tempDir

    Path projectDir
    Path factoryHome

    def setup() {
        projectDir = GnomishProjectFixture.writeGnomishProject(tempDir.resolve('project'))
        factoryHome = tempDir.resolve('gnomish-home')
    }

    /**
     * A one-shot render never legitimately touches the sleeper -- that's only used by the watch
     * loop -- so the default here kills the loop thread on its first wait rather than handing out a
     * real {@code ThreadSleeper}: a mutant that wrongly routes a one-shot run into the watch loop
     * then ends fast and leaves a watch-mode page the one-shot features reject, instead of
     * rendering forever behind a guard that absorbs an ordinary exception.
     */
    private DashboardCommand newCommand(RecordingReadOnlyTracker tracker, String instanceName = INSTANCE_NAME) {
        DashboardCommandFixture.command(factoryHome, projectDir, tracker, DashboardCommandFixture.killingSleeper(), instanceName)
    }

    def "defaults the output path to dashboard.html in the instance's observability directory"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString()))

        then:
        def expected = factoryHome.resolve('projects/widgets/serve').resolve(INSTANCE_NAME).resolve('dashboard.html')
        Files.exists(expected)
    }

    // Scenario: observability/dashboard-page / Default output lands beside the observability files
    def "FR10 of add-project-registry: default output lands beside the observability files"() {
        given: 'project widgets with the default instance name'
        def command = newCommand(new RecordingReadOnlyTracker([], []), null)

        when: 'the dashboard runs without --out'
        command.run(args('dashboard', "--dir=${projectDir}".toString()))

        then: 'the page is written to GNOMISH_HOME/projects/widgets/serve/default/dashboard.html'
        Files.readString(factoryHome.resolve('projects/widgets/serve/default/dashboard.html')).startsWith('<!doctype html>')
    }

    // Scenario: observability/dashboard-page / Explicit output path
    def "honors an explicit --out path"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))
        def out = tempDir.resolve('incident.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        then:
        Files.exists(out)
        !Files.exists(factoryHome.resolve('projects/widgets/serve').resolve(INSTANCE_NAME).resolve('dashboard.html'))
    }

    // Scenario: observability/dashboard-page / One-shot snapshot never banners
    // Scenario: observability/dashboard-page / One-shot exits after a single render
    def "a one-shot render writes exactly one complete, self-contained page and returns normally"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))
        def out = tempDir.resolve('one-shot.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        then:
        noExceptionThrown()
        def html = Files.readString(out)
        html.startsWith('<!doctype html>')
        html.contains('</html>')

        and: 'a one-shot page carries no meta-refresh and never arms the stale degradation'
        html.contains('data-mode="oneshot"')
        !html.contains('http-equiv="refresh"')
    }

    // FR2, NFR-S1 of add-dashboard-page: the one-shot output is the whole page — copied anywhere,
    //     it names nothing outside its own bytes, so no companion file and no network is needed.
    // Scenario: observability/dashboard-page / Portable one-shot file
    def "a one-shot file copied elsewhere is byte-identical and references nothing outside itself"() {
        given:
        def out = tempDir.resolve('one-shot.html')
        newCommand(new RecordingReadOnlyTracker([], [])).run(
        args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        when: 'the file alone is carried to another machine'
        def elsewhere = Files.createDirectories(tempDir.resolve('another-machine')).resolve('dashboard.html')
        Files.copy(out, elsewhere)
        def html = Files.readString(elsewhere)

        then: 'it is the same page'
        Files.mismatch(out, elsewhere) == -1L

        and: 'nothing in it names a resource to load: no source, link, import, url() or network address'
        !(html =~ /(?i)\b(src|href|action|poster)\s*=/).find()
        !(html =~ /(?i)url\(|@import|https?:/).find()

        and: 'nor a path on the machine it was rendered on'
        !html.contains(tempDir.toString())
    }
}
