package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * What {@link ServeRuntimeAssembly} wires into the daemon, proven by the effect each wiring has on
 * a real {@code gnomish serve --drain} pass — a real git project, a real {@link InMemoryTracker}
 * and the fake agent binary — never by reading the private fields of the objects it built.
 *
 * <p>Each feature names the effect that exists only if the wiring does: the progress line a beat
 * writes into the claim (the heartbeat is real and its listener sees the engine's events), the
 * {@code taskOutcome} line in the ledger (the ledger writer is attached to the slot runner), and
 * the {@code tracker} section of the snapshot (the feed polls through the health decorator the
 * snapshot reads).
 *
 * <p>Implements FR13 of add-factory-serve. Implements FR8, FR11 of add-serve-observability.
 */
@Timeout(120)
class ServeRuntimeWiringSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final String INSTANCE_NAME = 'factory-01' // FakeAgentSupport.propertiesFor's instance name

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    /** Every progress payload a beat wrote, in order; appended to from the heartbeat thread. */
    List<String> beatPayloads = Collections.synchronizedList(new ArrayList<String>())
    InMemoryTracker tracker = new InMemoryTracker() {
        @Override
        HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
            beatPayloads << progressPayload
            super.heartbeat(ref, progressPayload)
        }
    }

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        // A beat interval far below the slow round's two seconds, so beats land mid-round.
        writeMinimalProject(projectDir, '100ms')
        commitAll(projectDir)
        // FR5, FR13 of add-base-ref-resolution: a real serve startup resolves and refreshes its
        // base against a real 'origin' remote, never the clone's local HEAD.
        addOrigin(projectDir, tempDir)
        worktreesRoot = tempDir.resolve('worktrees')
        homeDir = tempDir.resolve('home')
    }

    private void seedReadyTask() {
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('Add widgets'), UntrustedText.tracker('please add widgets')),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    private void drain(String scenario) {
        def properties = FakeAgentSupport.propertiesFor(scenario)
        newDrainCommand(properties, newAssembly(properties), worktreesRoot, homeDir, fakeFactory(tracker))
                .run(args('serve', "--dir=$projectDir", '--drain'))
    }

    def "FR13: the held claim is beaten by the real heartbeat, with the stage the engine reported"() {
        given: 'one Ready task and a round that stays in flight for a couple of seconds'
        seedReadyTask()

        when:
        drain('plain-round-slow')

        then: 'the round really ran to delivery'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'a beat reached the tracker — the no-op ClaimBeat.NONE would write none'
        !beatPayloads.isEmpty()

        and: "a beat carried the engine's own stage — only a progress listener joined into the " +
        'assembly the slot ran with can see it; without it every beat says (pending)'
        beatPayloads.any { it.startsWith('stage=build ') }
    }

    def "FR11: a delivered task leaves its taskOutcome line in the ledger"() {
        given:
        seedReadyTask()

        when:
        drain('plain-round')

        then: 'the round really ran to delivery'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'the slot runner wrote its outcome through the attached ledger writer'
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.now(ZoneOffset.UTC))
        readLedgerLines(ledgerFile)*.get('type')*.asText().count('taskOutcome') == 1
    }

    // Nothing but the feed touches the tracker on an empty drain: no claim means no beat, and the
    // standing reaper's first tick is a whole default interval away.
    def "FR8: the feed polls through the health decorator the snapshot reads"() {
        when: 'a drain over an empty queue'
        drain('plain-round')

        then: "the snapshot's tracker section records the feed's successful poll"
        def tracker = readJson(ObservabilityPaths.snapshotFile(homeDir, INSTANCE_NAME)).get('tracker')
        !tracker.get('lastSuccessAt').isNull()
        tracker.get('consecutiveFailures').asInt() == 0
    }
}
