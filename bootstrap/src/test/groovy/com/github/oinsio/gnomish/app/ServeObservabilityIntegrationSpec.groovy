package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Integration-level proof of FR9 of add-serve-observability: a full {@code gnomish serve --drain}
 * pass, driven through the real {@link ServeCommand} entry point with no mocked observability
 * collaborator, writes a real {@code snapshot.json} and ledger file in the registered project's
 * {@code projects/<name>/serve/<instance>} directory under a real temp factory home (FR10 of
 * add-project-registry) — the path keyed by the configured instance NAME (design D2), the full per-process
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
 * <p>Implements FR9 of add-serve-observability; FR10 of add-project-registry.
 */
class ServeObservabilityIntegrationSpec extends Specification
implements AppAssemblyFixture, ServeObservabilityFixture, BareGitRepoFixture {

    static final String INSTANCE_NAME = 'gnomish-observability'
    // FR10 of add-project-registry: the id begins with the project name, then the instance name
    static final String INSTANCE_ID_PATTERN = /^widgets-gnomish-observability-[0-9a-z]{6}$/

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir
    Tracker tracker = Mock()

    def setup() {
        // FR5, FR13 of add-base-ref-resolution: a real serve startup resolves and refreshes its
        // base against a real 'origin' remote, never a bare directory or the clone's local HEAD.
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        homeDir = tempDir.resolve('home')
    }

    ServeCommand newCommand(String instanceName = INSTANCE_NAME, Path clonePath = projectDir, String project = 'widgets') {
        def factoryProperties = testProperties(instanceName: instanceName)
        newDrainCommand(factoryProperties, newAssembly(factoryProperties),
                RegisteredCloneFixture.unregistered(homeDir, clonePath, project), fakeFactory(tracker))
    }

    /** The serve directory the design fixes for {@code project} and {@code instanceName}. */
    private Path serveDir(String project = 'widgets', String instanceName = INSTANCE_NAME) {
        homeDir.resolve('projects').resolve(project).resolve('serve').resolve(instanceName)
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
        def snapshotFile = ObservabilityPaths.snapshotFile(serveDir())
        Files.exists(snapshotFile)

        and: 'the full instance id — name plus per-process suffix — lives inside the data'
        def snapshot = readJson(snapshotFile)
        def instanceId = snapshot.get('instance').get('instanceId').asText()
        instanceId ==~ INSTANCE_ID_PATTERN
        snapshot.get('lifecycle').get('state').asText() == 'stopped'

        and: 'the daily ledger file exists at the same name-keyed directory'
        def ledgerFile = ObservabilityPaths.ledgerFile(serveDir(), today)
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

    @Timeout(20)
    def "FR10 of add-project-registry: two projects on one host keep separate state"() {
        given: 'a second project, gateway, with its own clone and origin'
        def gatewayDir = initWorkingRepo(tempDir, 'gateway')
        writeMinimalProject(gatewayDir)
        commitAll(gatewayDir)
        addOrigin(gatewayDir, Files.createDirectories(tempDir.resolve('gateway-remote')))

        and: 'daemons for widgets and gateway, both with the default instance name'
        def widgets = newCommand(null, projectDir, 'widgets')
        def gateway = newCommand(null, gatewayDir, 'gateway')
        tracker.listReady(_) >> []
        tracker.listOpen() >> []

        when:
        widgets.run(new DefaultApplicationArguments('serve', "--dir=$projectDir", '--drain'))
        gateway.run(new DefaultApplicationArguments('serve', "--dir=$gatewayDir", '--drain'))

        then: 'the snapshots live in projects/widgets/serve/default/ and projects/gateway/serve/default/'
        def widgetsSnapshot = readJson(ObservabilityPaths.snapshotFile(serveDir('widgets', 'default')))
        def gatewaySnapshot = readJson(ObservabilityPaths.snapshotFile(serveDir('gateway', 'default')))

        and: 'neither overwrites the other: each file carries the instance id of its own daemon'
        widgetsSnapshot.get('instance').get('instanceId').asText() !=
                gatewaySnapshot.get('instance').get('instanceId').asText()

        and: 'FR10 of add-project-registry: each id begins with its own project name, then the instance name'
        widgetsSnapshot.get('instance').get('instanceId').asText() ==~ /^widgets-default-[0-9a-z]{6}$/
        gatewaySnapshot.get('instance').get('instanceId').asText() ==~ /^gateway-default-[0-9a-z]{6}$/
    }
}
