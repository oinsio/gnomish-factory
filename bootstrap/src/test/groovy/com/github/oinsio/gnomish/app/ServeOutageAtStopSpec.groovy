package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.JsonNode
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.FixtureGitConfig
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * A remote that dies under a real {@code gnomish serve --drain} pass — a real git project, a real
 * {@link InMemoryTracker}, the shipped serve assembly — and what the daemon's observability files
 * keep of it. The remote dies after startup, in the instant the feed claims the task: the claim
 * itself re-points the clone's {@code origin} at a path that does not exist, so the slot's own base
 * refresh is the first read to meet it, exactly as a remote that died mid-run.
 *
 * <p>The slot releases its claim and nothing of that run reaches the ledger as an outcome. The
 * second feature needs the gate open when the drain stops — the final snapshot the one record of
 * it, no {@code remoteOutage} line, and a second daemon on the same project knowing nothing of it.
 * The first claim-time read to meet the dead remote is the task-branch lookup, so the gate opens
 * from the lookup's outage as well as from a base refresh (finding F88 of tighten-spec-contract).
 *
 * <p>Implements NFR-O1, NFR-O3, FR14 of add-base-ref-resolution.
 */
@Timeout(120)
class ServeOutageAtStopSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String INSTANCE_NAME = 'factory-01' // FakeAgentSupport.propertiesFor's instance name

    @TempDir
    Path tempDir

    Path projectDir
    Path homeDir
    Path origin
    volatile boolean remoteDiesOnClaim = true

    InMemoryTracker tracker = new InMemoryTracker() {
        @Override
        ClaimResult claim(TaskRef ref, String instanceId) {
            if (remoteDiesOnClaim) {
                FixtureGitConfig.setRemoteUrl(projectDir, 'origin', tempDir.resolve('gone.git').toString())
            }
            super.claim(ref, instanceId)
        }
    }

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        commitAll(projectDir)
        origin = addOrigin(projectDir, tempDir)
        homeDir = tempDir.resolve('home')
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please add widgets')),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    private Path serveDir() {
        homeDir.resolve("projects/${RegisteredCloneFixture.PROJECT}/serve/${INSTANCE_NAME}")
    }

    private void drain() {
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        newDrainCommand(properties, newAssembly(properties),
                RegisteredCloneFixture.unregistered(homeDir, projectDir), fakeFactory(tracker))
                .run(args('serve', "--dir=$projectDir", '--drain'))
    }

    /** Every line of every ledger file the daemon wrote, whatever day the wall clock said. */
    private List<JsonNode> ledgerLines() {
        Files.list(serveDir()).withCloseable { files ->
            files.filter {
                it.fileName.toString().startsWith('ledger-')
            }.toList()
        }.collectMany { readLedgerLines(it) }
    }

    private JsonNode remoteSection() {
        readJson(ObservabilityPaths.snapshotFile(serveDir())).get('remote').get('origin')
    }

    // Scenario: observability/serve-observability / Released task leaves no outcome line
    def "NFR-O1: a slot released on the dead remote writes no taskOutcome line, and its outcome log says released"() {
        given:
        def slotLog = LogCaptureSupport.attach(TakeSlotRunner, Level.DEBUG)

        when:
        drain()

        then: 'the slot released its claim on the dead remote and reported it in its outcome log'
        slotLog.list.any {
            it.level == Level.DEBUG && it.formattedMessage.startsWith("slot for task ${REF.id()} released: ")
        }

        and: 'no taskOutcome line for the task: nothing ran and nothing was spent'
        ledgerLines().findAll {
            it.get('type').asText() == 'taskOutcome'
        }.isEmpty()

        cleanup:
        slotLog.detach()
    }

    // F88: a dead origin is met first by the claim-time task-branch lookup; its outage opens the gate too.
    // Scenario: observability/serve-observability / An outage open at stop writes no line
    def "NFR-O3: an outage still open at stop writes no remoteOutage line, and the next daemon starts closed"() {
        when: 'the drain stops with the gate open'
        drain()

        then: 'no remoteOutage line, and the final stopped snapshot shows the gate open'
        ledgerLines().findAll {
            it.get('type').asText() == 'remoteOutage'
        }.isEmpty()
        readJson(ObservabilityPaths.snapshotFile(serveDir())).get('lifecycle').get('state').asText() == 'stopped'
        remoteSection().get('state').asText() == 'open'

        when: 'the remote is back and a second daemon runs on the same project'
        remoteDiesOnClaim = false
        FixtureGitConfig.setRemoteUrl(projectDir, 'origin', origin.toString())
        drain()

        then: 'its gate starts closed and knows nothing of the first outage'
        def remote = remoteSection()
        remote.get('state').asText() == 'closed'
        remote.get('openSince').isNull()
        remote.get('lastError').isNull()
        remote.get('consecutiveFailures').asInt() == 0

        and: 'neither daemon ever wrote a remoteOutage line'
        ledgerLines().findAll {
            it.get('type').asText() == 'remoteOutage'
        }.isEmpty()
    }
}
