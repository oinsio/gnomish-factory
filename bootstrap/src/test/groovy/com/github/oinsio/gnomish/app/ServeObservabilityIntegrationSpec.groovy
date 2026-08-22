package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
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
 * Integration-level proof of FR9 of add-serve-observability: a full {@code gnomish serve --drain}
 * pass, driven through the real {@link ServeCommand} entry point with no mocked observability
 * collaborator, writes a real {@code snapshot.json} and ledger file under a real temp {@code
 * homeDir} — the path keyed by the configured instance NAME (design D2), the full per-process
 * {@code InstanceId} appearing only inside the written data. Every wiring piece exercised here
 * (writer thread, ledger appender, path resolution) already exists from task 5.1 — this spec
 * proves the assembled whole, not any one collaborator in isolation ({@code
 * ObservabilityAssemblySpec}/{@code ObservabilityWiringSpec} cover those at the unit level). The
 * restart half of FR9/M3 — same paths, new suffix across two runs — lives in the companion {@link
 * ServeObservabilityRestartIntegrationSpec} (process-invariants.md file-size cap).
 *
 * <p>The tracker is mocked to return an empty ready/open queue: drain then claims nothing, so the
 * ledger's {@code taskOutcome} line is deliberately out of scope here (already covered by {@code
 * TaskOutcomeLedgerWriterSpec}) — this spec's job is proving the {@code lifecycle}/{@code
 * runSummary} write points and the path/identity contract survive a real end-to-end wiring, not
 * re-proving per-line content already covered at the unit level.
 *
 * <p>Implements FR9 of add-serve-observability.
 */
class ServeObservabilityIntegrationSpec extends Specification
implements AppAssemblyFixture, ServeObservabilityFixture {

    static final String INSTANCE_NAME = 'gnomish-observability'
    static final String INSTANCE_ID_PATTERN = /^gnomish-observability-[0-9a-z]{6}$/

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

    ServeCommand newCommand() {
        new ServeCommand(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                new ServeProperties(1, null, null, null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: fakeFactory(tracker)],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }

    @Timeout(10)
    def "a full drain pass writes a snapshot and ledger with consistent instance identity (FR9)"() {
        given:
        def command = newCommand()
        def today = LocalDate.now(ZoneOffset.UTC)

        when:
        command.run(new DefaultApplicationArguments('serve', "--dir=$projectDir", '--drain'))

        then: 'the drain path polled once and claimed nothing'
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []
        0 * tracker.claim(_, _)

        and: 'the snapshot lands at the path keyed by instance NAME, not the full id'
        def snapshotFile = ObservabilityPaths.snapshotFile(homeDir, INSTANCE_NAME)
        Files.exists(snapshotFile)

        and: 'the full instance id — name plus per-process suffix — lives inside the data'
        def snapshot = readJson(snapshotFile)
        def instanceId = snapshot.get('instance').get('instanceId').asText()
        instanceId ==~ INSTANCE_ID_PATTERN
        snapshot.get('lifecycle').get('state').asText() == 'stopped'

        and: 'the daily ledger file exists at the same name-keyed directory'
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, today)
        Files.exists(ledgerFile)

        and: 'a started lifecycle line, the drain-only runSummary line, then the stopped lifecycle line — each valid JSON, carrying the SAME instance id as the snapshot'
        def lines = readLedgerLines(ledgerFile)
        lines*.get('type')*.asText() == [
            'lifecycle',
            'runSummary',
            'lifecycle'
        ]
        lines.collect { instanceIdOf(it) }.unique() == [instanceId]
        lines[0].get('event').asText() == 'started'
        lines[2].get('event').asText() == 'stopped'
        lines[2].get('reason').asText() == 'drainComplete'
    }
}
