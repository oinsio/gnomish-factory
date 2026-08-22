package com.github.oinsio.gnomish.adapter.agent

import static com.github.oinsio.gnomish.adapter.agent.NonEndingStreams.nonEndingStream

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, NFR-R2 of fix-round-stdout-drain, executor side — the twin of {@code
 * JudgeRoundDrainTimeoutSpec}. Where the judge maps an unfinished drain to a
 * {@code CannotVerify} vote, the executor throws: {@code RoundExecution#execute}
 * shapes any {@link RuntimeException} the port raises into {@code
 * RoundOutcome.CannotExecute} without burning a stage attempt, so the contract
 * this side owns is that the failure leaves as an exception of the right kind —
 * never as a silently partial event list graded as a real round.
 */
class ExecutorRoundDrainTimeoutSpec extends Specification {

    @TempDir
    Path workspaceDir

    /** Released in cleanup so a spinning stand-in stream never outlives its feature. */
    def stuck = new AtomicBoolean()

    def cleanup() {
        stuck.set(true)
    }

    // FR2: the grace expires while the drain is still reading — an infrastructure failure.
    def "a drain that outlives the tail-drain grace throws StreamDrainTimeoutException"() {
        given: 'a process that exited normally but whose stdout never ends'
        def handle = new ExitedExecHandle(nonEndingStream(stuck))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }

        when:
        runRound(environment, Duration.ofMillis(100))

        then: 'the round fails on the grace, naming the property an operator would raise'
        def e = thrown(StreamDrainTimeoutException)
        e.message.contains('tail-drain-grace')
    }

    // FR2: an interrupted round thread is the drain's other infrastructure failure, and
    // the executor must not report it as a grace that was too short.
    def "an interrupted round thread throws StreamDrainInterruptedException, not a timeout"() {
        given:
        def handle = new ExitedExecHandle(nonEndingStream(stuck))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }

        and: 'the round thread carries a pending interrupt when it comes to await the drain'
        Thread.currentThread().interrupt()

        when:
        runRound(environment, Duration.ofSeconds(30))

        then:
        def e = thrown(StreamDrainInterruptedException)
        !e.message.contains('tail-drain-grace')

        cleanup:
        Thread.interrupted()
    }

    private void runRound(TaskExecutionEnvironment environment, Duration grace) {
        ExecutorRoundExecution.run(
                new FactoryProperties('factory-01', 'claude', grace, [], null, null, null),
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(),
                new DecisionFileReader(),
                request(),
                'prompt',
                new StandInRound(environment, workspaceDir.resolve('decision.json')))
    }

    private StageExecutor.Request request() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}

/** A handle whose process has already exited cleanly, over a caller-supplied stdout. */
class ExitedExecHandle implements ExecHandle {

    private final InputStream stdout

    ExitedExecHandle(InputStream stdout) {
        this.stdout = stdout
    }

    @Override
    InputStream output() {
        stdout
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
        new Wait.Exited(Duration.ofSeconds(1))
    }

    @Override
    int waitForExit() {
        0
    }
}

/** The host round reduced to what a drain-failure round reaches: its environment and paths. */
class StandInRound implements RoundEnvironmentSource.Round {

    private final TaskExecutionEnvironment environment
    private final Path decisionFile

    StandInRound(TaskExecutionEnvironment environment, Path decisionFile) {
        this.environment = environment
        this.decisionFile = decisionFile
    }

    @Override
    TaskExecutionEnvironment environment() {
        environment
    }

    @Override
    Path decisionFilePath() {
        decisionFile
    }

    @Override
    Map<String, String> decisionEnvFragment() {
        [:]
    }

    @Override
    void closeRound() {
    }

    @Override
    Optional<String> readDecision() {
        Optional.empty()
    }

    @Override
    void discard() {
    }
}
