package com.github.oinsio.gnomish.adapter.tracker

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.AbortLifecycleFixture
import com.github.oinsio.gnomish.app.TakeCommandFixture
import com.github.oinsio.gnomish.app.TakeCommands
import com.github.oinsio.gnomish.app.TakeExitCodeException
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` "Bare auto mode takes the head of the queue": a bare {@code take} whose every
 * ready task is still inside its abort backoff. The queue is one shared {@link InMemoryTracker};
 * the command stands at {@link TakeCommands#START} with the fixture's 2-minute backoff base.
 *
 * <p>FR10 of add-tracker-port; FR6 of add-factory-serve.
 */
class InMemoryTakeNothingEligibleSpec extends Specification implements AbortLifecycleFixture, TakeCommandFixture {

    @TempDir
    Path tempDir

    def setup() {
        def inMemory = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(inMemory)
        ['PROJ-1', 'PROJ-2'].each { id ->
            harness.seed(new TaskRef(id), new TaskSnapshot(id, UntrustedText.tracker('title'), UntrustedText.tracker('body')),
            new TrackerTaskState.Ready(), new AbortFacts(1, TakeCommands.START.minusSeconds(30)))
        }
        tracker = inMemory
        trackerFactory = new FixedTrackerAdapterFactory({ inMemory })
        writeAbortProjectFixture()
    }

    // FR10: every ready task is hidden by its backoff, so nothing is eligible.
    // F76: the no-op names its reason in one INFO line (BareTakeClaimWalk), no longer silent.
    // Scenario: tracker/tracker-take / Nothing eligible is a clean no-op naming the reason
    def "a bare take over a queue hidden entirely by backoff exits clean, naming that nothing is eligible"() {
        given:
        def properties = testProperties(agentCliBinary: FakeAgentSupport.propertiesFor('plain-round').agentCliBinary(),
        tracker: new FactoryProperties.Tracker(BACKOFF_BASE, BACKOFF_CAP))
        def logs = LogCaptureSupport.attach('ROOT', Level.INFO)

        when:
        newTakeCommand(properties, registeredClone, [github: trackerFactory]).run(takeArgs('take', "--dir=$projectDir"))

        then: 'a clean no-op: nothing was claimed, and the process exits 0'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0
        tracker.listOpen().isEmpty()

        and: 'the invocation names the reason: no ready task is eligible'
        logs.list*.formattedMessage.any {
            it.toLowerCase().contains('eligible')
        }

        cleanup:
        logs?.detach()
    }
}
