package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Integration-level proof of M3 of add-serve-observability (the "restart" half of FR9): TWO
 * separate {@link ServeCommand} lifetimes — simulating a daemon restart — driven over the SAME
 * temp {@code homeDir} and the SAME configured instance name each write to the SAME {@code
 * snapshot.json} path and the SAME daily ledger file (design D2: the directory is keyed by the
 * stable instance NAME), while the {@code instance.instanceId} carried inside the written data
 * differs between the two runs — each process mints its own {@code InstanceId} suffix (design D6
 * of add-tracker-port) at {@link ServeCommand#run} time. See the companion {@link
 * ServeObservabilityIntegrationSpec} for the single-pass identity/shape proof this spec builds on
 * (process-invariants.md file-size cap kept the two apart).
 *
 * <p>Implements FR9, M3 of add-serve-observability.
 */
class ServeObservabilityRestartIntegrationSpec extends Specification implements AppAssemblyFixture {

    private static final String INSTANCE_NAME = 'gnomish-observability-restart'
    private static final String INSTANCE_ID_PATTERN = /^gnomish-observability-restart-[0-9a-z]{6}$/
    private static final ObjectMapper MAPPER = new ObjectMapper()

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    Tracker tracker = Mock()

    def setup() {
        projectDir = tempDir.resolve('project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
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
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        worktreesRoot = tempDir.resolve('worktrees')
        homeDir = tempDir.resolve('home')
    }

    /** Drain never drives the forever-loop starter; fails loudly if that ever changes. */
    private static class RefusingStarter implements FeedAutomatonStarter {
        @Override
        void start(FeedAutomaton automaton) {
            throw new IllegalStateException('drain must never use the forever-loop starter')
        }
    }

    private ServeCommand newCommand() {
        new ServeCommand(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                new ServeProperties(1, null, null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: fakeFactory(tracker)],
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter())
    }

    private static List<JsonNode> readLedgerLines(Path ledgerFile) {
        ledgerFile.toFile().readLines('UTF-8').findAll {
            !it.isBlank()
        }.collect {
            MAPPER.readTree(it)
        }
    }

    private static String instanceIdOf(JsonNode line) {
        line.get('instance').get('instanceId').asText()
    }

    private static String snapshotInstanceId(Path snapshotFile) {
        MAPPER.readTree(snapshotFile.toFile()).get('instance').get('instanceId').asText()
    }

    @Timeout(10)
    def "a restart against the same instance name keeps both paths and writes a new suffix into the data (M3)"() {
        given: 'two ServeCommand instances over the SAME homeDir/instance name, simulating two process lifetimes'
        def firstRun = newCommand()
        def secondRun = newCommand()
        def today = LocalDate.now(ZoneOffset.UTC)
        def snapshotFile = ObservabilityPaths.snapshotFile(homeDir, INSTANCE_NAME)
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, today)

        when: 'the first run drains to completion'
        firstRun.run(new DefaultApplicationArguments('serve', "--dir=$projectDir", '--drain'))

        then:
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and:
        def firstInstanceId = snapshotInstanceId(snapshotFile)
        def linesAfterFirstRun = readLedgerLines(ledgerFile)
        linesAfterFirstRun.size() == 3
        firstInstanceId ==~ INSTANCE_ID_PATTERN

        when: '"restarting" — a second process, over the same homeDir/instance name, also drains'
        secondRun.run(new DefaultApplicationArguments('serve', "--dir=$projectDir", '--drain'))

        then:
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and: 'the snapshot path is unchanged, and its data now carries a DIFFERENT per-process suffix'
        Files.exists(snapshotFile)
        def secondInstanceId = snapshotInstanceId(snapshotFile)
        secondInstanceId ==~ INSTANCE_ID_PATTERN
        secondInstanceId != firstInstanceId

        and: 'the ledger continues in the SAME file — appended, not replaced — with the second run\'s own id'
        Files.exists(ledgerFile)
        def linesAfterSecondRun = readLedgerLines(ledgerFile)
        linesAfterSecondRun.size() == 6
        linesAfterSecondRun[0..2].collect {
            instanceIdOf(it)
        }.unique() == [firstInstanceId]
        linesAfterSecondRun[3..5].collect {
            instanceIdOf(it)
        }.unique() == [secondInstanceId]
    }
}
