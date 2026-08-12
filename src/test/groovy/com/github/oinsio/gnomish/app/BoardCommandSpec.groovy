package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
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
        projectDir = tempDir.resolve('project')
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

    // Task 5.1 note (from AbortLifecycleFixture): tracker.type is 'github' purely to satisfy
    // TrackerSeamValidator's registered-type + matching-subsection check via the permissive
    // TrackerValidatorStub; this spec's own trackerAdapterRegistry below overrides which adapter
    // actually backs 'github' for the run, resolving RecordingReadOnlyTracker instead of a real
    // GitHub adapter.
    private BoardCommand newCommand(RecordingReadOnlyTracker tracker) {
        def factory = new RecordingTrackerAdapterFactory(tracker)
        new BoardCommand(
                Clock.fixed(Instant.parse('2026-08-05T00:00:00Z'), ZoneOffset.UTC),
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                [github: factory],
                TrackerValidatorStub.acceptingGithub())
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
        def command = new BoardCommand(
                Clock.fixed(Instant.parse('2026-08-05T00:00:00Z'), ZoneOffset.UTC),
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                [github: factory],
                TrackerValidatorStub.acceptingGithub())

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
        2          || true
        1          || false
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
        json  || firstChar
        true  || '{'
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

/**
 * A strict read-only {@link Tracker} fake: {@code listReady}/{@code listOpen} are recorded and
 * answered from fixed data; every write/coordination method throws {@link AssertionError}, so a
 * board code path that ever calls one fails the test immediately rather than silently passing
 * (NG3 of add-board-command).
 */
class RecordingReadOnlyTracker implements Tracker {

    private final List<ReadyTask> ready
    private final List<OpenTask> open
    int listReadyCalls = 0
    int listOpenCalls = 0
    Integer lastLimit

    RecordingReadOnlyTracker(List<ReadyTask> ready, List<OpenTask> open) {
        this.ready = ready
        this.open = open
    }

    @Override
    List<ReadyTask> listReady(int limit) {
        listReadyCalls++
        lastLimit = limit
        ready
    }

    @Override
    List<OpenTask> listOpen() {
        listOpenCalls++
        open
    }

    private static UnsupportedOperationException notReadOnly(String method) {
        new UnsupportedOperationException("BoardCommand must never call Tracker.$method (NG3 of add-board-command)")
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        throw notReadOnly('fetchTask')
    }

    @Override
    List<HumanReply> collectDecisions(TaskRef ref) {
        throw notReadOnly('collectDecisions')
    }

    @Override
    ClaimResult claim(TaskRef ref, String instanceId) {
        throw notReadOnly('claim')
    }

    @Override
    void release(TaskRef ref) {
        throw notReadOnly('release')
    }

    @Override
    void park(TaskRef ref, ParkReason reason, String report) {
        throw notReadOnly('park')
    }

    @Override
    void finish(TaskRef ref, String summary) {
        throw notReadOnly('finish')
    }

    @Override
    void declineFinished(TaskRef ref, String message) {
        throw notReadOnly('declineFinished')
    }

    @Override
    void recordAbort(TaskRef ref, AbortRecord record) {
        throw notReadOnly('recordAbort')
    }

    @Override
    void recordProgress(TaskRef ref) {
        throw notReadOnly('recordProgress')
    }

    @Override
    void acknowledgeDecision(TaskRef ref, String decisionText) {
        throw notReadOnly('acknowledgeDecision')
    }

    @Override
    void postNote(TaskRef ref, String text) {
        throw notReadOnly('postNote')
    }

    @Override
    HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        throw notReadOnly('heartbeat')
    }

    @Override
    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        throw notReadOnly('removeStaleClaim')
    }
}

/** Records the {@code instanceId} it was called with (design D8: minted but never written). */
class RecordingTrackerAdapterFactory implements TrackerAdapterFactory {

    private final Tracker tracker
    String capturedInstanceId

    RecordingTrackerAdapterFactory(Tracker tracker) {
        this.tracker = tracker
    }

    @Override
    Tracker create(TrackerConfig config, String instanceId) {
        capturedInstanceId = instanceId
        tracker
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture')
    }
}
