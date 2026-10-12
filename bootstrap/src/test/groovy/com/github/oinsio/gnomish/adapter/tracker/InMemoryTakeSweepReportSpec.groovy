package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.AbortLifecycleFixture
import com.github.oinsio.gnomish.app.TakeCommandFixture
import com.github.oinsio.gnomish.app.TakeExitCodeException
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.sandboxlifecycle.Slf4jSweepVerdictListener
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepSummaryListener
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` "Startup sweep pass with a reported summary": what a take invocation reports of
 * its startup pass. The pass stands in for the Docker sweep only where the sweep reaches a verdict:
 * it hands one stopped box and two aged remnants to the production sinks — the structured
 * per-verdict line ({@link Slf4jSweepVerdictListener}) and the tally ({@link SweepSummaryListener})
 * — exactly as the real pass fans them out, and returns the tally's line to {@code take}. The task
 * itself is delivered by the fake agent over one shared {@link InMemoryTracker}.
 *
 * <p>FR6, FR9, NFR-O4 of add-serve-sandbox-lifecycle.
 */
class InMemoryTakeSweepReportSpec extends Specification implements AbortLifecycleFixture, TakeCommandFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    private InMemoryTracker inMemory

    def setup() {
        inMemory = new InMemoryTracker()
        new InMemoryTrackerHarness(inMemory).seed(REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('Add widgets'),
                UntrustedText.tracker('please add widgets')), new TrackerTaskState.Ready(), AbortFacts.none())
        tracker = inMemory
        trackerFactory = new FixedTrackerAdapterFactory({ inMemory })
        writeAbortProjectFixture()
    }

    /** One stopped abandoned box and two disposed aged remnants, through the production sinks. */
    private static final SandboxLifecyclePass ONE_STOPPED_TWO_DISPOSED = { Path dir, LivenessVerdict liveness ->
        def summary = new SweepSummaryListener(new Slf4jSweepVerdictListener())
        summary.onVerdict(new SweepVerdict(SweepVerdictCategory.STOPPED_ORPHAN, 'gnomish-box-t1', 'main-box', 'tracked', 't1',
                'unowned running main box', Duration.ofHours(3), null))
        ['t2', 't3'].each {
            summary.onVerdict(new SweepVerdict(SweepVerdictCategory.DISPOSED_AGED, "gnomish-vol-${it}".toString(), 'volume',
            'tracked', it, 'unowned remnant past the reap age', Duration.ofDays(9), null))
        }
        summary.summaryLine()
    } as SandboxLifecyclePass

    private int takeDelivering() {
        def properties = testProperties(agentCliBinary: FakeAgentSupport.propertiesFor('plain-round').agentCliBinary())
        try {
            newTakeCommand(properties, registeredClone, [github: trackerFactory], { time ->
                com.github.oinsio.gnomish.app.TakeCommandSeams.defaults(time, com.github.oinsio.gnomish.app.LiveConsoleIO.onStdout())
            }, ONE_STOPPED_TWO_DISPOSED).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))
            throw new IllegalStateException('take returned without an exit code')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    // NFR-O4: one summary line in the invocation's log, and one structured line per action.
    // Scenario: tracker/tracker-take / Take reports its sweep
    def "the invocation logs the sweep's summary and a structured line per action"() {
        given:
        def takeLog = LogCaptureSupport.attach('com.github.oinsio.gnomish.app.TakeCommand')
        def sweepLog = LogCaptureSupport.attach('gnomish.sandbox.lifecycle')

        when:
        def exit = takeDelivering()

        then:
        exit == 0

        and: 'one summary line names one stopped and two disposed objects'
        takeLog.list*.formattedMessage.findAll { it.contains('sweep:') } == [
            'gnomish take: sweep: 1 stopped-orphan, 2 disposed-aged'
        ]

        and: 'each action is one structured line naming its object, task key and reason'
        def actions = sweepLog.list*.formattedMessage
        actions.size() == 3
        actions[0].contains('object=gnomish-box-t1') && actions[0].contains('task=t1') && actions[0].contains('reason="unowned running main box"')
        actions[1].contains('object=gnomish-vol-t2') && actions[1].contains('task=t2') && actions[1].contains('reason="unowned remnant past the reap age"')
        actions[2].contains('object=gnomish-vol-t3') && actions[2].contains('task=t3')

        cleanup:
        takeLog?.detach()
        sweepLog?.detach()
    }

    // NFR-O4: the finish report describes the task; the sweep is project-wide.
    // Scenario: tracker/tracker-take / The sweep summary stays out of the finish report
    def "the sweep summary is in the invocation's log and not in the delivered task's finish report"() {
        given:
        def takeLog = LogCaptureSupport.attach('com.github.oinsio.gnomish.app.TakeCommand')

        when:
        def exit = takeDelivering()

        then: 'the task was delivered, with a finish report'
        exit == 0
        def finish = new InMemoryTrackerHarness(inMemory).threadAsStrings(REF).find {
            it.startsWith('FINISH:')
        }
        finish != null

        and: 'the summary reached the log, and the report carries none of it'
        takeLog.list*.formattedMessage.any {
            it.contains('sweep: 1 stopped-orphan, 2 disposed-aged')
        }
        !finish.contains('sweep')
        !finish.contains('stopped-orphan')
        !finish.contains('disposed-aged')

        cleanup:
        takeLog?.detach()
    }
}
