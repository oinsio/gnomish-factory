package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.board.BoardComposition
import com.github.oinsio.gnomish.board.json.BoardJsonMapper
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-dashboard-page (task 2.2, design D7): the dashboard's future board section (task
 * 4.x) will resolve its own {@link com.github.oinsio.gnomish.app.port.tracker.Tracker}/{@link
 * TrackerConfig} from {@code --dir} exactly as {@link BoardCommand} does and then call {@link
 * BoardComposition#compose} directly, so this spec proves the seam both callers share actually
 * agrees with {@code BoardCommand}'s own model for the same tracker state: {@link BoardCommand}
 * is run end-to-end (the closest thing to ground truth for "what {@code gnomish board} shows"
 * until the real dashboard caller exists), and its JSON output is compared against a standalone
 * {@link BoardComposition#compose} call standing in for the dashboard — same {@link
 * com.github.oinsio.gnomish.app.port.tracker.Tracker} fixture, same {@link TrackerConfig}
 * resolution, same {@link Clock}, same {@code readyLimit} (the board CLI's 50-row default,
 * chosen here since task 4.x has not yet fixed the dashboard's own limit — a reasonable stand-in
 * that also avoids truncation with this spec's small fixture). The fixture includes an in-backoff
 * Ready row so the comparison actually exercises equal backoff deadlines, not just structurally
 * empty agreement.
 *
 * <p>Implements FR5 of add-dashboard-page.
 */
class BoardCompositionAgreementSpec extends Specification implements ApplicationArgumentsFixture {

    private static final String INSTANCE_NAME = 'board-instance'
    private static final Instant NOW = Instant.parse('2026-08-05T00:00:00Z')
    private static final Instant BACKOFF_LAST_ABORT_AT = NOW - Duration.ofMinutes(1)

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
  wip-limit: 3
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
    }

    def "the dashboard's BoardComposition.compose call agrees with gnomish board's own model for the same tracker state, including the in-backoff deadline"() {
        given: 'the same tracker state (an in-backoff Ready row, a plain Ready row, a Working row, an AwaitingHuman row) shared by both callers'
        def ready = [
            new ReadyTask(new TaskRef('r-1'), new AbortFacts(1, BACKOFF_LAST_ABORT_AT), false, false, 'Fix flaky spec'),
            new ReadyTask(new TaskRef('r-2'), AbortFacts.none(), false, false, 'Add widgets')
        ]
        def open = [
            new OpenTask(new TaskRef('w-1'), new TrackerTaskState.Working('someone'),
            new ClaimVersion('marker-1', NOW - Duration.ofMinutes(3)), 'Refactor retry module'),
            new OpenTask(new TaskRef('h-1'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null, 'Needs a decision')
        ]
        def tracker = new RecordingReadOnlyTracker(ready, open)
        def factory = new RecordingTrackerAdapterFactory(tracker)
        def clock = Clock.fixed(NOW, ZoneOffset.UTC)
        def factoryProperties = new FactoryProperties(
                INSTANCE_NAME, null, null, new FactoryProperties.Tracker(Duration.ofMinutes(2), Duration.ofHours(1)), null)
        def trackerValidatorRegistry = TrackerValidatorStub.acceptingGithub()
        def boardCommand = new BoardCommand(clock, factoryProperties, [github: factory], trackerValidatorRegistry)

        and: 'the board CLI\'s default readyLimit (50), stood in for as the dashboard\'s own choice too'
        int readyLimit = 50

        when: 'gnomish board is run end-to-end, as BoardCommand would be invoked, capturing its JSON output'
        def out = new ByteArrayOutputStream()
        def originalOut = System.out
        System.out = new PrintStream(out)
        boardCommand.run(args('board', "--dir=${projectDir}".toString(), '--json'))
        System.out = originalOut
        String commandJson = out.toString().trim()

        and: 'the dashboard resolves its own Tracker/TrackerConfig from the same --dir and calls BoardComposition.compose directly'
        PipelineDefinition definition = TakeCommandSupport.loadPipeline(projectDir, trackerValidatorRegistry)
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition)
        def dashboardModel = BoardComposition.compose(tracker, trackerConfig, factoryProperties.tracker(), clock, readyLimit)
        String dashboardJson = new BoardJsonMapper().serialize(dashboardModel, trackerConfig.wipLimit())

        then: 'both callers agree on the exact same board data, deadline included'
        dashboardJson == commandJson

        and: 'the agreement is not vacuous: the in-backoff deadline is actually present on both sides'
        String expectedDeadline = (BACKOFF_LAST_ABORT_AT + Duration.ofMinutes(2)).toString()
        commandJson.contains(expectedDeadline)
        dashboardJson.contains(expectedDeadline)

        cleanup:
        System.out = originalOut
    }
}
