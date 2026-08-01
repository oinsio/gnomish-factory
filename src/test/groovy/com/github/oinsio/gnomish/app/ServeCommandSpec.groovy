package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * FR2, FR4, FR12, D3, D7 of add-factory-serve (task 5.1): {@link ServeCommand}'s startup
 * label-provisioning smoke test — a tracker binding {@code create()} cannot reach fails startup
 * (exit 1) before anything is claimed; a reachable binding proceeds to assemble and start the
 * scheduler. The blocking {@link FeedAutomaton#run()} itself is never exercised here: a fake
 * {@link FeedAutomatonStarter} (this task's own test seam) captures the assembled automaton
 * instead of running it, so these specs never block on a real feed loop.
 */
class ServeCommandSpec extends Specification implements AppAssemblyFixture, BareGitRepoFixture {

    private static final String INSTANCE_NAME = 'gnomish-factory'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
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
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private void writeConfig(String trackerSection = '') {
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\n$trackerSection")
    }

    private static final String GITHUB_TRACKER_SECTION = '''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
'''

    // No `repo` key at all — TrackerValidatorStub.acceptingGithub() accepts any subsection
    // content, so this parses fine and exercises bindingDescription's repo == null branch.
    private static final String GITHUB_TRACKER_SECTION_NO_REPO = '''
tracker:
  type: github
  github:
    api-url: https://api.github.com
'''

    private static String captureStderr(Closure action) {
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.err = originalErr
        }
        return captured.toString('UTF-8')
    }

    private static TrackerAdapterFactory factoryReturning(Tracker t) {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        t
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    private static TrackerAdapterFactory factoryThrowingOnCreate(RuntimeException failure) {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        throw failure
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    /** A {@link FeedAutomatonStarter} that captures the built automaton without ever running it. */
    private static class CapturingStarter implements FeedAutomatonStarter {
        FeedAutomaton captured

        @Override
        void start(FeedAutomaton automaton) {
            captured = automaton
        }
    }

    private ServeCommand newCommand(Map<String, TrackerAdapterFactory> registry, FeedAutomatonStarter starter) {
        new ServeCommand(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                worktreesRoot,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                new ServeProperties(0, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                registry,
                TrackerValidatorStub.acceptingGithub(),
                starter)
    }

    private static org.springframework.boot.DefaultApplicationArguments args(String... raw) {
        new org.springframework.boot.DefaultApplicationArguments(raw)
    }

    // Non-termination guard: run() assembles a REAL FeedAutomaton whose outage retry (NFR-R3)
    // never gives up, over a real ThreadSleeper. A mutant that hands it a broken tracker (e.g.
    // provisionTracker returning null, or the drain flag negated so a Mock-backed tracker feeds
    // the drain loop nulls) spins that retry forever — PIT could only report TIMED_OUT, which
    // pitestVerifyAllKilled rejects. Running the command on a bounded virtual (daemon) thread
    // turns the hang into a red assertion instead.
    private static void runsToCompletion(Closure body) {
        def failure = new java.util.concurrent.atomic.AtomicReference<Throwable>()
        def worker = Thread.ofVirtual().name('serve-command-under-test').start {
            try {
                body()
            } catch (Throwable t) {
                failure.set(t)
            }
        }
        if (!worker.join(Duration.ofSeconds(10))) {
            worker.interrupt()
            throw new AssertionError('ServeCommand.run did not complete within 10s — non-termination (runaway outage retry or leaked permit)' as Object)
        }
        if (failure.get() != null) {
            throw failure.get()
        }
    }

    def "no tracker section in config.yaml refuses with UsageException (FR17)"() {
        given:
        writeConfig()
        def command = newCommand([:], new CapturingStarter())

        when:
        command.run(args('serve', "--dir=$projectDir"))

        then:
        def ex = thrown(UsageException)
        ex.message.contains('tracker')
    }

    def "unreachable tracker binding fails startup with exit code 1 before claiming anything"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryThrowingOnCreate(new IllegalStateException('connection refused'))
        def command = newCommand([github: factory], new CapturingStarter())
        ServeExitCodeException ex

        when:
        def stderr = captureStderr {
            try {
                command.run(args('serve', "--dir=$projectDir"))
            } catch (ServeExitCodeException caught) {
                ex = caught
            }
        }

        then:
        ex != null
        ex.exitCode() == 1
        0 * tracker.listReady(_)
        0 * tracker.claim(_, _)

        and: 'bindingDescription (FR12, D7) names both the type and the configured repo'
        stderr.contains("gnomish serve: startup failed provisioning tracker 'github' (acme/widgets): connection refused")
    }

    // FR12, D7: bindingDescription's other branch — when no `repo` key is configured, the
    // startup-failure message names only the tracker type, with no parenthesized repo suffix,
    // proving the `repo == null` conditional (and not just its negation) is actually exercised.
    def "startup failure message names only the tracker type when no repo is configured"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION_NO_REPO)
        def factory = factoryThrowingOnCreate(new IllegalStateException('connection refused'))
        def command = newCommand([github: factory], new CapturingStarter())
        ServeExitCodeException ex

        when:
        def stderr = captureStderr {
            try {
                command.run(args('serve', "--dir=$projectDir"))
            } catch (ServeExitCodeException caught) {
                ex = caught
            }
        }

        then:
        ex != null
        ex.exitCode() == 1

        and: 'no parenthesized repo suffix — a distinct, non-empty message from the repo-configured branch'
        stderr.contains("gnomish serve: startup failed provisioning tracker 'github': connection refused")
        !stderr.contains('(')
    }

    def "a reachable tracker binding proceeds to assemble and start the scheduler"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryReturning(tracker)
        def starter = new CapturingStarter()
        def command = newCommand([github: factory], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then: 'the command returns normally: the fake starter never drove a real feed cycle'
        noExceptionThrown()
        starter.captured != null

        and: 'the assembled feed polls the very tracker the startup smoke test created (FR12) — not a null'
        starter.captured.@cycle.@tracker.is(tracker)

        and: 'no claim attempt was ever made — the scheduler was assembled but never actually run'
        0 * tracker.listReady(_)
        0 * tracker.claim(_, _)
    }

    // FR13: serve wires the REAL cross-slot heartbeat/claim-loss-flag (TakeHeartbeat.forRun) into
    // the slot runner it assembles, and joins the heartbeat's progress listener into the assembly
    // BEFORE the (reused-for-the-daemon's-lifetime) slot runner is built — not the ClaimBeat.NONE/
    // disposable-listener seam task 5.1 wired as a placeholder. Reaches through the private fields
    // task 4.3's TakeSlotRunner/TakeClaimAndWork already carry (no new production seam needed) via
    // Groovy's ".@" direct field access, since neither class exposes these for inspection otherwise.
    def "wires the real cross-slot heartbeat and claim-loss flag into the assembled slot runner (FR13)"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryReturning(tracker)
        def starter = new CapturingStarter()
        def command = newCommand([github: factory], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then:
        noExceptionThrown()
        TakeSlotRunner slotRunner = starter.captured.@slotRunner
        def claimAndWork = slotRunner.@claimAndWork
        ClaimBeat heartbeat = claimAndWork.@heartbeat
        def joinedAssembly = claimAndWork.@assembly

        and: 'the beat is the real InstanceHeartbeat, not the ClaimBeat.NONE no-op seam'
        heartbeat instanceof InstanceHeartbeat

        and: "the heartbeat's progress listener already joined the assembly handed to the slot runner"
        joinedAssembly.@extraListener instanceof HeartbeatProgress
    }

    // FR10, NFR-O2, M3 (task 5.4): --drain takes a wholly different path than the ordinary
    //     forever-loop starter — it drives FeedAutomaton#drain() directly and returns normally
    //     once the (here: empty) queue drains, exercising M3's "--drain on an empty queue exits 0
    //     with an empty-run report" without ever touching the CapturingStarter.
    def "--drain drives the drain path instead of starter.start, claiming nothing on an empty queue"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryReturning(tracker)
        def starter = new CapturingStarter()
        def command = newCommand([github: factory], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir", '--drain')) }

        then: 'the ordinary forever-loop starter was never invoked'
        noExceptionThrown()
        starter.captured == null

        and: 'ServeShutdownWiring.runDrain really ran automaton.drain(), which polled exactly once'
        1 * tracker.listReady(_) >> []
        1 * tracker.listOpen() >> []

        and: 'the drain path polled once, found nothing eligible, and claimed nothing'
        0 * tracker.claim(_, _)
    }

    // FR14, D10 (task 5.2): proves ServeCommand#run really calls WorktreeJanitor::start — not
    // merely assembles the janitor — via the only externally observable effect a fire-and-forget
    // janitor thread has: it disposes of a real, aged, unheld worktree shortly after startup. No
    // test seam exists for the janitor (ServeAssembly.worktreeJanitor wires a real ThreadSleeper/
    // SystemClock, out of scope for this file to change), so this drives a real `git worktree
    // add`/`git worktree remove --force` round trip and polls for the directory's disappearance.
    def "the worktree janitor is actually started and disposes an aged unheld worktree on startup"() {
        given: 'projectDir is a real git repo with one commit, and a registered, aged worktree'
        def gitRunner = new GitProcessRunner()
        assert gitRunner.run(projectDir, 'init').exitCode() == 0
        commitAll(projectDir, 'init')
        def worktreePath = worktreesRoot.resolve('project').resolve('aged-task')
        Files.createDirectories(worktreePath.parent)
        assert gitRunner.run(projectDir, 'worktree', 'add', worktreePath.toString(), '-b', 'task/aged-task').exitCode() == 0
        def aged = FileTime.from(Instant.now() - Duration.ofDays(1))
        Files.walk(worktreePath).filter { Files.isRegularFile(it) }.forEach { Files.setLastModifiedTime(it, aged) }
        Files.setLastModifiedTime(worktreePath, aged)

        and: 'a config with a tiny worktree-age threshold, so the immediate startup tick disposes it'
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryReturning(tracker)
        def command = new ServeCommand(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                worktreesRoot,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                new ServeProperties(0, null, null, Duration.ofMillis(1)),
                Clock.systemUTC(),
                new SystemClock(),
                [github: factory],
                TrackerValidatorStub.acceptingGithub(),
                new CapturingStarter())

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then: 'the janitor thread actually ran its startup tick and removed the aged worktree'
        new PollingConditions(timeout: 5, initialDelay: 0, delay: 0.1).eventually {
            assert !Files.exists(worktreePath)
        }
    }

    def "--slots overrides ServeProperties#slots() without failing SlotLedger construction"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryReturning(tracker)
        def starter = new CapturingStarter()
        def command = newCommand([github: factory], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir", '--slots=3')) }

        then:
        noExceptionThrown()
        starter.captured != null
    }
}
