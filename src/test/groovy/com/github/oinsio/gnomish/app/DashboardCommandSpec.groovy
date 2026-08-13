package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.dashboard.DashboardWatchLoop
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR1, FR3, NFR-R2 of add-dashboard-page (task 4.1-4.3, design D8): {@link DashboardCommand}
 * resolves the tracker from {@code --dir} exactly like {@link BoardCommand}, defaults its output
 * to {@code dashboard.html} in the instance's observability directory (design D8), honors an
 * explicit {@code --out}, writes atomically, and completes a one-shot render normally — exit zero
 * — even when the tracker is unreachable (FR3's degraded-board case, never an exception).
 */
class DashboardCommandSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE_NAME = 'dashboard-instance'

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir

    def setup() {
        projectDir = tempDir.resolve('project')
        homeDir = tempDir.resolve('home')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  abort-threshold: 3
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
    }

    /**
     * A one-shot render never legitimately touches the sleeper -- that's only used by the watch
     * loop -- so the default here throws immediately rather than handing out a real {@code
     * ThreadSleeper}: a mutant that wrongly routes a one-shot run into the watch loop would
     * otherwise block on real 10s sleeps forever instead of failing fast.
     */
    private DashboardCommand newCommand(RecordingReadOnlyTracker tracker) {
        def sleeper = { Duration d ->
            throw new IllegalStateException('sleeper must not be used in one-shot mode')
        } as Sleeper
        newCommand(tracker, sleeper)
    }

    private DashboardCommand newCommand(RecordingReadOnlyTracker tracker, Sleeper sleeper) {
        new DashboardCommand(
                Clock.fixed(Instant.parse('2026-08-06T00:00:00Z'), ZoneOffset.UTC),
                sleeper,
                homeDir,
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                [github: new RecordingTrackerAdapterFactory(tracker)],
                TrackerValidatorStub.acceptingGithub())
    }

    def "defaults the output path to dashboard.html in the instance's observability directory"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString()))

        then:
        def expected = homeDir.resolve('.gnomish/serve').resolve(INSTANCE_NAME).resolve('dashboard.html')
        Files.exists(expected)
    }

    def "honors an explicit --out path"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))
        def out = tempDir.resolve('incident.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        then:
        Files.exists(out)
        !Files.exists(homeDir.resolve('.gnomish/serve').resolve(INSTANCE_NAME).resolve('dashboard.html'))
    }

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

        and: 'a one-shot page never bakes the watch-mode staleness banner'
        !html.contains('id="staleness-banner"')
    }

    def "a fresh install with no snapshot and an empty tracker still renders the page normally"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker([], []))
        def out = tempDir.resolve('fresh.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        then:
        noExceptionThrown()
        Files.readString(out).contains('daemon has not run here')
    }

    @Timeout(5)
    def "--watch enters the watch loop instead of rendering once"() {
        given: 'a sleeper that records the requested duration and stops the otherwise-infinite loop'
        def sleptDurations = []
        def sleeper = { Duration d ->
            sleptDurations << d; throw new RuntimeException('stop after one cycle')
        } as Sleeper
        def command = newCommand(new RecordingReadOnlyTracker([], []), sleeper)
        def out = tempDir.resolve('watch.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString(), '--watch'))

        then:
        thrown(RuntimeException)
        Files.exists(out)
        sleptDurations == [
            DashboardWatchLoop.RENDER_CADENCE
        ]

        and: 'a watch-mode page bakes the staleness banner that a one-shot page never does'
        Files.readString(out).contains('id="staleness-banner"')
    }

    def "a tracker outage degrades only the board section, exits zero, and never retries"() {
        given:
        def tracker = new RecordingReadOnlyTracker([], []) {
            @Override
            List<com.github.oinsio.gnomish.app.port.tracker.ReadyTask> listReady(int limit) {
                super.listReady(limit)
                throw new RuntimeException('tracker unreachable')
            }
        }
        def command = newCommand(tracker)
        def out = tempDir.resolve('outage.html')

        when:
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString()))

        then:
        noExceptionThrown()
        def html = Files.readString(out)
        html.contains('unavailable')
        html.contains('tracker unreachable')

        and: 'exactly one attempt was made for a one-shot render — no built-in retry loop'
        tracker.listReadyCalls == 1
    }
}
