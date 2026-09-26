package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The project, tracker and command fixture every {@link ServeCommand} startup spec shares: a real
 * git project with a real {@code origin}, a mocked {@link Tracker} behind the {@code github}
 * provider, and a {@code CapturingStarter} that captures the assembled {@link FeedAutomaton}
 * instead of running it, so no spec ever blocks on a real feed loop.
 *
 * <p>Subclasses split by what they prove about startup: {@link ServeStartupFailureSpec} (the
 * command refuses or exits 1), {@link ServeLaunchSpec} (which path it takes and with what
 * configuration), {@link ServeBackgroundDutiesSpec} (the daemon-lifetime threads it starts).
 *
 * <p>Implements FR2, FR4, FR12, D3, D7 of add-factory-serve.
 */
abstract class ServeCommandSpecBase extends Specification
implements AppAssemblyFixture, BareGitRepoFixture, ApplicationArgumentsFixture {

    protected static final String INSTANCE_NAME = 'gnomish-factory'

    protected static final String GITHUB_TRACKER_SECTION = '''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
'''

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    Tracker tracker = Mock()

    def setup() {
        // A real git repo with a real 'origin' (FR5, FR13 of add-base-ref-resolution): serve's own
        // startup resolves and refreshes its base against origin's default branch before it can
        // load a definition at all, never from a bare directory or the clone's local HEAD.
        projectDir = initWorkingRepo(tempDir, 'project')
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
        commitAll(projectDir, 'init')
        addOrigin(projectDir, tempDir)
        worktreesRoot = tempDir.resolve('worktrees')
        homeDir = tempDir.resolve('home')
    }

    protected void writeConfig(String trackerSection = '') {
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\n$trackerSection")
        // FR13, D14 of add-base-ref-resolution: a real serve startup reads its definition from git
        // objects at origin's refreshed tip, never from the checkout.
        commitAll(projectDir, 'config')
        pushOrigin(projectDir)
    }

    /** A {@link FeedAutomatonStarter} that captures the built automaton without ever running it. */
    protected static class CapturingStarter implements FeedAutomatonStarter {
        FeedAutomaton captured

        @Override
        void start(FeedAutomaton automaton) {
            captured = automaton
        }
    }

    protected ServeCommand newCommand(
            Map<String, TrackerAdapterFactory> registry,
            FeedAutomatonStarter starter,
            ServeProperties serveProperties = new ServeProperties(0, null, null, null, null, null, null, null, null)) {
        new ServeCommand(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                serveProperties,
                Clock.systemUTC(),
                new SystemClock(),
                registry,
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                starter, SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(), LiveConsoleIO.onStderr())
    }

    // Non-termination guard: run() assembles a REAL FeedAutomaton whose outage retry (NFR-R3)
    // never gives up, over a real ThreadSleeper. A mutant that hands it a broken tracker (e.g.
    // provisionTracker returning null, or the drain flag negated so a Mock-backed tracker feeds
    // the drain loop nulls) spins that retry forever — PIT could only report TIMED_OUT, which
    // pitestVerifyAllKilled rejects. Running the command on a bounded virtual (daemon) thread
    // turns the hang into a red assertion instead.
    protected static void runsToCompletion(Closure body) {
        def failure = new AtomicReference<Throwable>()
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
}
