package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R1 of add-board-command: when the tracker is unreachable, {@code gnomish board} adds no
 * retry loop of its own and relies entirely on the existing generic reporting {@link
 * RunExceptionReporting} already gives every subcommand (task 3.3) — proven here end to end
 * through {@link ManualRunRunner#run}, the real dispatch path a live invocation takes.
 */
class BoardCommandOutageSpec extends Specification {

    private static final String INSTANCE_NAME = 'board-instance'

    @TempDir
    Path tempDir

    @TempDir
    Path worktreesRoot

    @TempDir
    Path homeDir

    Path projectDir

    def setup() {
        projectDir = tempDir.resolve('project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
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
  abort-threshold: 3
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
    }

    private ManualRunRunner newRunner(BoardCommand boardCommand) {
        new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(TrackerValidatorStub.plainSource()),
                new AdHocTaskSynthesizer(Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]),
                DiscoveredBindings.real(),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                new StatusCommand(TaskGitFixture.real(), worktreesRoot),
                new UsageCommand(TaskGitFixture.real()),
                boardCommand,
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), homeDir,
                new FactoryProperties(INSTANCE_NAME, null, null, null, null), [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource()),
                Clock.systemUTC(),
                [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource(),
                new ServeProperties(0, null, null, null, null, null, null),
                new ClaimEpochBook())
    }

    // NFR-R1: the tracker outage message ("gnomish run failed: <adapter message>") reaches stderr
    // as a single line, and the exception propagates unchanged so RunExitCodeMapper's own spec can
    // map it to a non-zero exit code (out of scope to re-prove that mapping here).
    def "gnomish board prints one clear tracker-outage line to stderr and exits non-zero, with no retry"() {
        given: 'a tracker whose listReady fails as if the tracker endpoint refuses connections'
        def tracker = new OutageTracker()
        def factory = new OutageTrackerAdapterFactory(tracker)
        def boardCommand = new BoardCommand(
                Clock.systemUTC(),
                new FactoryProperties(INSTANCE_NAME, null, null, null, null),
                [github: factory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource())
        def runner = newRunner(boardCommand)
        def args = new DefaultApplicationArguments('board', "--dir=${projectDir}".toString())
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')

        when:
        TrackerUnavailableException thrown = null
        try {
            runner.run(args)
        } catch (TrackerUnavailableException ex) {
            thrown = ex
        } finally {
            System.err = originalErr
        }

        then: 'the exception propagates unclassified, so the default exit-code mapping (non-zero) applies'
        thrown != null

        and: 'exactly one clear, single line was printed to stderr naming the tracker as the cause'
        def lines = captured.toString('UTF-8').split(System.lineSeparator())
        lines.length == 1
        lines[0] == "gnomish run failed: ${thrown.message}".toString()
        lines[0].toLowerCase().contains('tracker')

        and: 'BoardCommand added no retry loop of its own — the tracker was asked exactly once'
        tracker.listReadyCalls == 1
    }
}

/** Always fails {@code listReady} as if the tracker endpoint refuses connections (NFR-R1). */
class OutageTracker implements Tracker {

    int listReadyCalls = 0

    @Override
    List<ReadyTask> listReady(int limit) {
        listReadyCalls++
        throw new TrackerUnavailableException('tracker unreachable: connection refused')
    }

    @Override
    List<OpenTask> listOpen() {
        throw new AssertionError('BoardCommand must not call listOpen after listReady fails' as Object)
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    List<HumanReply> collectDecisions(TaskRef ref) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    ClaimResult claim(TaskRef ref, String instanceId) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void release(TaskRef ref) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void park(TaskRef ref, ParkReason reason, String report) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void finish(TaskRef ref, String summary) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void declineFinished(TaskRef ref, String message) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void recordAbort(TaskRef ref, AbortRecord record) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void recordProgress(TaskRef ref) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void acknowledgeDecision(TaskRef ref, String decisionText) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    void postNote(TaskRef ref, String text) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimFacts observedClaim) {
        throw new UnsupportedOperationException('not used by this fixture')
    }

    @Override
    RepairIndexResult repairIndex(TaskRef ref, TrackerFacts observedFacts) {
        throw new UnsupportedOperationException('not used by this fixture')
    }
}

class OutageTrackerAdapterFactory implements TrackerAdapterFactory {

    private final Tracker tracker

    OutageTrackerAdapterFactory(Tracker tracker) {
        this.tracker = tracker
    }

    @Override
    String type() {
        'github'
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        tracker
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture')
    }
}
