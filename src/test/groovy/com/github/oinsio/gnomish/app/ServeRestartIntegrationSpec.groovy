package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * fix-reaper-idle-liveness FR12 of add-factory-serve ("Restart against an empty queue still
 * recovers"), the claim-heartbeat scenario "A restarted daemon returns its previous life's claims
 * with nothing to claim", and UX1: the layer above {@code
 * com.github.oinsio.gnomish.app.lease.RestartCleanlinessSpec} — that spec proves the mechanics by
 * manually ticking a {@code StandingReaper}; this one drives a REAL {@link ServeCommand} end to
 * end (a real {@link FeedAutomaton} on its own daemon thread, a real standing reaper on its own
 * interval) over the real {@link InMemoryTracker}, so the reaper's own thread and interval — not a
 * test-driven tick — are what returns two prior-life stale claims to circulation.
 *
 * <p>A previous life claimed two tasks, then died — seeded directly via {@link
 * InMemoryTrackerHarness#seedWorkingWithClaim}, bypassing {@code claim} exactly like a claim a now
 * -dead process actually made. The restarted daemon mints a fresh instance id and starts against an
 * otherwise empty ready queue (no task is ever {@code Ready} until the reaper puts one there), so it
 * genuinely claims nothing of its own at startup. Once the claims' TTL (heartbeat interval × 3)
 * elapses on the real clock, the standing reaper alone returns both to {@code Ready}, and the
 * ordinary feed loop — not the reaper — re-claims them under the new instance.
 *
 * <p>Implements FR12 of add-factory-serve; FR1, FR2, FR5, NFR-R1 of fix-reaper-idle-liveness.
 */
class ServeRestartIntegrationSpec extends Specification implements AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final TaskRef TASK_A = new TaskRef('github:o/r#restart-a')
    private static final TaskRef TASK_B = new TaskRef('github:o/r#restart-b')
    private static final String OLD_INSTANCE_ID = 'gnomish-factory-dead-previous-life'

    // heartbeat-interval 100ms x the default TTL multiplier (3) = 300ms TTL: long enough that the
    // "not yet adopted" check below is never racy, short enough to keep this spec fast.
    private static final int PRE_TTL_SLEEP_MILLIS = 120

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        homeDir = tempDir.resolve('home')
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
  heartbeat-interval: 100ms
''')
        worktreesRoot = tempDir.resolve('worktrees')

        // The "previous life": two claims held by a now-dead instance id, seeded directly —
        // never through claim() — exactly like RestartCleanlinessSpec's own setup.
        harness.seedWorkingWithClaim(tracker, TASK_A, OLD_INSTANCE_ID)
        harness.seedWorkingWithClaim(tracker, TASK_B, OLD_INSTANCE_ID)
    }

    def "a restarted serve never adopts the previous life's claims, and the standing reaper alone returns both to circulation (FR12, UX1)"() {
        given: 'a fresh serve daemon, its own instance id, over the seeded tracker — ready queue empty'
        def command = new ServeCommand(
                newAssembly(testProperties(instanceName: 'gnomish-factory')),
                worktreesRoot,
                homeDir,
                'taskId',
                testProperties(instanceName: 'gnomish-factory'),
                new ServeProperties(2, Duration.ofMillis(20), null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: fakeFactory(tracker)],
                TrackerValidatorStub.acceptingGithub(), { FeedAutomaton automaton ->
                    automaton.run()
                } as FeedAutomatonStarter)
        def failure = new AtomicReference<Throwable>()
        def worker = Thread.ofVirtual().name('serve-restart-integration-under-test').start {
            try {
                command.run(args('serve', "--dir=$projectDir"))
            } catch (Throwable t) {
                failure.set(t)
            }
        }

        when: 'well within the 300ms TTL, the restarted daemon has had time to start but not to reap'
        Thread.sleep(PRE_TTL_SLEEP_MILLIS)

        then: 'the previous life\'s claims are untouched — not adopted, not claimed by the new instance'
        tracker.fetchTask(TASK_A).state() == new TrackerTaskState.Working(OLD_INSTANCE_ID)
        tracker.fetchTask(TASK_B).state() == new TrackerTaskState.Working(OLD_INSTANCE_ID)
        failure.get() == null

        when: 'the TTL elapses and the standing reaper, then the ordinary feed loop, run on their own'
        new PollingConditions(timeout: 5, initialDelay: 0, delay: 0.05).eventually {
            def stateA = tracker.fetchTask(TASK_A).state()
            def stateB = tracker.fetchTask(TASK_B).state()
            assert stateA instanceof TrackerTaskState.Working && stateA.holder() != OLD_INSTANCE_ID
            assert stateB instanceof TrackerTaskState.Working && stateB.holder() != OLD_INSTANCE_ID
        }

        then: 'both were reaped and re-claimed by the new instance through the ordinary queue — no manual tick, no exception leaked'
        failure.get() == null

        cleanup: 'stop the real feed thread so this spec never leaves a spinning claim loop behind'
        Thread.getAllStackTraces().keySet()
                .findAll { it.name == ServeShutdownWiring.FEED_THREAD_NAME }
                .each { it.interrupt() }
        worker.join(5000)
    }
}
