package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
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

    /** Ended in cleanup so a live stand-in child's output never outlives its feature. */
    def stuck = FakeProcess.running()

    def cleanup() {
        stuck.exit(0)
    }

    // FR2; FR19 of fix-terminal-receipt-convergence: the handle's post-exit bound runs out while
    // the drain is still reading — an infrastructure failure, on the handle's bound and no other.
    // Scenario: executor/agent-executor / A drain unfinished after the post-exit bound is infrastructure
    def "a drain that outlives the handle's post-exit bound throws StreamDrainTimeoutException"() {
        given: 'a process that exited normally but whose stdout never ends, its handle bounding the wait at 100 ms'
        def handle = new ExitedExecHandle(CannedOutputs.nonEnding(stuck), Duration.ofMillis(100))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> handle
        }

        when:
        runRound(environment)

        then: 'the round fails on the bound the handle answered, not on an installation default'
        def failure = thrown(ExecutorFailure)
        def e = failure.cause()
        e instanceof StreamDrainTimeoutException
        e.message.contains('PT0.1S')
    }

    // FR2: an interrupted round thread is the drain's other infrastructure failure, and
    // the executor must not report it as a bound that ran out.
    // Scenario: executor/agent-executor / An interrupted wait is not blamed on the bound
    def "an interrupted round thread throws StreamDrainInterruptedException, not a timeout"() {
        given:
        def handle = new ExitedExecHandle(CannedOutputs.nonEnding(stuck), Duration.ofSeconds(30))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> handle
        }

        and: 'the round thread carries a pending interrupt when it comes to await the drain'
        Thread.currentThread().interrupt()

        when:
        runRound(environment)

        then:
        def failure = thrown(ExecutorFailure)
        def e = failure.cause()
        e instanceof StreamDrainInterruptedException
        !e.message.contains('PT30S')

        cleanup:
        Thread.interrupted()
    }

    private void runRound(TaskExecutionEnvironment environment) {
        ExecutorRoundExecution.run(
                new AgentRoundEquipment(
                        new FactoryProperties('factory-01', 'claude', null, null),
                        new VirtualClock(),
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new DecisionFileReader(),
                StageExecutorRequests.request(workspaceDir),
                'prompt',
                new StandInRound(environment, workspaceDir.resolve('decision.json')))
    }
}

/**
 * A handle whose process has already exited cleanly, over a caller-supplied stdout and post-exit
 * bound — the one wait a round gives its drain after the exit (FR19 of
 * fix-terminal-receipt-convergence).
 */
class ExitedExecHandle implements ExecHandle {

    private final ChildOutput stdout
    private final Duration postExitBound

    ExitedExecHandle(ChildOutput stdout, Duration postExitBound = Duration.ofSeconds(1)) {
        this.stdout = stdout
        this.postExitBound = postExitBound
    }

    @Override
    ChildOutput output() {
        stdout
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
        new Wait.Exited(Duration.ofSeconds(1))
    }

    @Override
    int waitForExit() {
        0
    }

    @Override
    Duration postExitBound() {
        postExitBound
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
