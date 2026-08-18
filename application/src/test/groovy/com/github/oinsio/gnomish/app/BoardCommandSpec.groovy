package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, NFR-S1 of add-board-command (task 3.2, design D8): {@link BoardCommand} resolves the
 * tracker from {@code --dir}'s {@code .gnomish/config.yaml} exactly like {@code take}/{@code
 * serve}, mints a throwaway {@link com.github.oinsio.gnomish.app.port.tracker.InstanceId} purely
 * to satisfy {@link TrackerAdapterFactory#create}'s constructor contract, and calls only {@code
 * listReady}/{@code listOpen} — NEVER a write method (NG3). {@link RecordingReadOnlyTracker}
 * fails the test the instant any write method is invoked, so NG3 is proven by construction, not
 * merely by the test happening to pass.
 */
class BoardCommandSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE_NAME = 'board-instance'

    @TempDir
    Path tempDir

    Path projectDir

    def setup() {
        projectDir = GnomishProjectFixture.writeGnomishProject(tempDir.resolve('project'))
    }

    // Task 5.1 note (from AbortLifecycleFixture): tracker.type is 'github' purely to satisfy
    // TrackerSeamValidator's registered-type + matching-subsection check via the permissive
    // TrackerValidatorStub; this spec's own trackerAdapterRegistry below overrides which adapter
    // actually backs 'github' for the run, resolving RecordingReadOnlyTracker instead of a real
    // GitHub adapter.
    private static BoardCommand newCommand(RecordingReadOnlyTracker tracker) {
        commandBackedBy(new RecordingTrackerAdapterFactory(tracker))
    }

    private static BoardCommand commandBackedBy(RecordingTrackerAdapterFactory factory) {
        new BoardCommand(
                Clock.fixed(Instant.parse('2026-08-05T00:00:00Z'), ZoneOffset.UTC),
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                [github: factory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource())
    }

    def "resolves the tracker from --dir's config, minting an InstanceId passed to the factory, and calls only listReady/listOpen"() {
        given:
        def ready = [
            new ReadyTask(new TaskRef('t-1'), AbortFacts.none(), false, false, 'Add widgets')
        ]
        def open = [
            new OpenTask(new TaskRef('t-2'), new TrackerTaskState.Working('someone'), null, 'Fix gizmos')
        ]
        def tracker = new RecordingReadOnlyTracker(ready, open)
        def factory = new RecordingTrackerAdapterFactory(tracker)
        def command = commandBackedBy(factory)

        when:
        command.run(args('board', "--dir=${projectDir}".toString()))

        then: 'the tracker was resolved with a minted, non-blank instance id — never used to write'
        factory.capturedInstanceId != null
        factory.capturedInstanceId.startsWith(INSTANCE_NAME)

        and: 'exactly one listReady and one listOpen call were made (NFR-P1), nothing else'
        tracker.listReadyCalls == 1
        tracker.listOpenCalls == 1
        tracker.lastLimit == 50
    }

    def "passes --limit through to listReady"() {
        given:
        def tracker = new RecordingReadOnlyTracker([], [])
        def command = newCommand(tracker)

        when:
        command.run(args('board', "--dir=${projectDir}".toString(), '--limit=7'))

        then:
        tracker.lastLimit == 7
    }

    // FR3 (task 3.2): truncated is `ready.size() == limit`. A fetch that fills the window exactly
    // is reported truncated; a short fetch is not.
    def "marks the window truncated only when the fetched ready count equals the limit"() {
        given:
        def ready = (1..readyCount).collect {
            new ReadyTask(new TaskRef("r-$it".toString()), AbortFacts.none(), false, false, "t-$it".toString())
        }
        def command = newCommand(new RecordingReadOnlyTracker(ready, []))
        def out = new ByteArrayOutputStream()
        def originalOut = System.out
        System.out = new PrintStream(out)

        when:
        command.run(args('board', "--dir=${projectDir}".toString(), '--limit=2'))

        then:
        out.toString().contains('truncated') == expectTruncated

        cleanup:
        System.out = originalOut

        where:
        readyCount || expectTruncated
        2 || true
        1 || false
    }

    // FR6 (task 3.2): the --json flag selects the JSON projection; its absence selects the text
    // board. The two surfaces are distinct, so the ternary is proven to branch on the flag.
    def "emits JSON with --json and the text board without it"() {
        given:
        def command = newCommand(new RecordingReadOnlyTracker(
                        [
                            new ReadyTask(new TaskRef('r-1'), AbortFacts.none(), false, false, 'Add widgets')
                        ], []))
        def out = new ByteArrayOutputStream()
        def originalOut = System.out
        System.out = new PrintStream(out)

        when: 'run once with --json and once without'
        def baseFlags = [
            'board',
            "--dir=${projectDir}".toString()
        ]
        command.run(args((json ? baseFlags + '--json' : baseFlags) as String[]))

        then:
        def printed = out.toString().trim()
        printed.startsWith(firstChar)
        printed.startsWith('{') == json

        cleanup:
        System.out = originalOut

        where:
        json || firstChar
        true || '{'
        false || 'Ready ('
    }

    def "output is a smoke-testable summary containing ready/working ids and titles"() {
        given:
        def ready = [
            new ReadyTask(new TaskRef('ready-42'), AbortFacts.none(), false, false, 'Add widgets')
        ]
        def open = [
            new OpenTask(new TaskRef('working-7'), new TrackerTaskState.Working('someone'), null, 'Fix gizmos')
        ]
        def tracker = new RecordingReadOnlyTracker(ready, open)
        def command = newCommand(tracker)
        def out = new ByteArrayOutputStream()
        def originalOut = System.out
        System.out = new PrintStream(out)

        when:
        command.run(args('board', "--dir=${projectDir}".toString()))

        then:
        def printed = out.toString()
        printed.contains('ready-42')
        printed.contains('Add widgets')
        printed.contains('working-7')
        printed.contains('Fix gizmos')

        cleanup:
        System.out = originalOut
    }
}
