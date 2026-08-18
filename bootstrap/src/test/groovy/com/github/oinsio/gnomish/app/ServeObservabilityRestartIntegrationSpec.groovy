package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
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
class ServeObservabilityRestartIntegrationSpec extends Specification
implements AppAssemblyFixture, ServeObservabilityFixture {

    private static final String INSTANCE_NAME = 'gnomish-observability-restart'
    private static final String INSTANCE_ID_PATTERN = /^gnomish-observability-restart-[0-9a-z]{6}$/

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    Tracker tracker = Mock()

    def setup() {
        projectDir = tempDir.resolve('project')
        writeMinimalProject(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
        homeDir = tempDir.resolve('home')
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
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter())
    }

    private static String snapshotInstanceId(Path snapshotFile) {
        readJson(snapshotFile).get('instance').get('instanceId').asText()
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
