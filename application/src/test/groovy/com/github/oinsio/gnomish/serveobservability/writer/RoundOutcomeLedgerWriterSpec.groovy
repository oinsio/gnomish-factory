package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundExecutor
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.json.LedgerJson
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RoundOutcomeLedgerWriter} (NFR-O2, UX3 of define-executor-contract): one {@code
 * roundOutcome} line per executor round, written from the engine's own event stream on a real
 * ledger file, and an append failure that never reaches the round.
 *
 * <p>The program round is the engine's: the stage-engine is executor-agnostic, so a scripted
 * executor stands in for the process, and the run's {@link DescribedExecutors} names the stage's
 * {@code program} executor with the version its describe answered — what the describe preflight
 * (task 6.2) hands the engine. The adapter-level line of a real {@code ProgramExecutor} round is
 * task 7.1's to assert.
 */
class RoundOutcomeLedgerWriterSpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-10-30T09:00:00Z')
    static final InstanceInfo INSTANCE = new InstanceInfo('gnome-1', 'host1', '1.0.0')
    static final TaskContext CONTEXT =
    new TaskContext('TASK-9', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    @TempDir
    Path homeDir

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()

    private Path serveDir() {
        homeDir.resolve('serve')
    }

    private RoundOutcomeLedgerWriter writer() {
        new RoundOutcomeLedgerWriter(ledgerAppenderFor(serveDir(), NOW), INSTANCE, new VirtualClock(NOW))
    }

    private List<Map> ledgerLines() {
        def file = ledgerFileFor(serveDir(), NOW)
        (Files.exists(file) ? Files.readAllLines(file) : []).collect {
            LedgerJson.mapper().readValue(it, Map)
        }
    }

    /** Runs the lint stage, naming {@code executorName} when given (a program stage), else agent-cli. */
    private void runLintStage(DescribedExecutors described, String executorName = null) {
        def mechanism = executorName == null
                ? new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:])
                : new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), executorName)
        def stage = new StageDefinition('lint', 'purpose', [], [], mechanism,
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ], new AutonomyLimits(3), AdvancementMode.AUTO)
        def clock = new VirtualClock(NOW)
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), writer(), new InMemoryAttemptPersistence(), VirtualTimeEquipment.on(clock),
                AttemptDelivery.assumedDelivered(), described, RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [stage]), CONTEXT,
        TaskState.atStageStart('lint'), new FakeWorkspace(), ports)
    }

    private static ExecutionResult result(ExecutionResult.Status status, int round) {
        new ExecutionResult(status, null, ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-9', 'lint', round), []), [])
    }

    // Scenario: pipeline/stage-engine / Ledger carries executor identity
    // NFR-O2, UX3: a completed program round's line names the executor, its version, the role and the status
    def "a completed program stage round writes one roundOutcome line naming executor, version, role and status"() {
        given: 'the lint stage names lint-bot, which the run describes at version 2.3.1'
        def described = new DescribedExecutors(
                new ExecutorVersions(['lint-bot': UntrustedText.subprocess('2.3.1')]), com.github.oinsio.gnomish.domain.engine.ExecutorCredentialNames.none())
        executor.scripted << result(new ExecutionResult.Completed(), 0)
        builtinRunner.scripted << new Verdict.Pass()

        when:
        runLintStage(described, 'lint-bot')

        then:
        def lines = ledgerLines()
        lines.size() == 1
        lines[0] == [
            version : 1,
            type : 'roundOutcome',
            instance: [instanceId: 'gnome-1', host: 'host1', factoryVersion: '1.0.0'],
            at : '2026-10-30T09:00:00Z',
            taskId : 'TASK-9',
            stage : 'lint',
            attempt : 0,
            executor: 'lint-bot@2.3.1',
            role : 'transform',
            status : 'completed'
        ]
    }

    // NFR-O2, UX3: every round has its line — a quality retry, then an unrecorded infrastructure failure
    def "each round writes its own line, the failure classes included"() {
        given:
        executor.scripted << result(new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, UntrustedText.subprocess('red'), []), 0)
        executor.scripted << result(new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.subprocess('down'), []), 1)
        def logs = LogCaptureSupport.attach('com.github.oinsio.gnomish.domain.engine.RoundFailures', Level.WARN)

        when:
        runLintStage(DescribedExecutors.none())

        then:
        ledgerLines().collect {
            [
                it.attempt,
                it.executor,
                it.status
            ]
        } == [
            [
                0,
                'agent-cli',
                'failed/quality'
            ],
            [
                1,
                'agent-cli',
                'failed/infrastructure'
            ]
        ]
        logs.list*.formattedMessage.any {
            it.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        }

        cleanup:
        logs.detach()
    }

    // NFR-O2: only the round event writes a line
    def "no other engine event writes a line"() {
        when:
        writer().onEvent(new EngineEvent.AttemptStarted(new AttemptKey('TASK-9', 'lint', 0)))
        writer().onEvent(new EngineEvent.RunStarted('TASK-9', new Position.AtStage('lint'), 0))

        then:
        ledgerLines().isEmpty()
    }

    // NFR-R1 of add-serve-observability: an append failure never reaches the round
    def "an append failure is swallowed and leaves one ERROR carrying the catalog code and the task"() {
        given: 'a regular file where the ledger directory belongs, so every append fails'
        Files.writeString(serveDir(), 'not a directory')
        def logs = LogCaptureSupport.attach(RoundOutcomeLedgerWriter)
        def finished = new EngineEvent.ExecutionFinished(new AttemptKey('TASK-9', 'lint', 0), Participants.transform('agent-cli', ExecutorUsage.none()),
                new RoundExecutor('agent-cli', null), new ExecutionResult.Completed())

        when:
        writer().onEvent(finished)

        then:
        noExceptionThrown()
        def events = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.ROUND_OUTCOME_LEDGER_APPEND_FAILED.head())
        }
        events.size() == 1
        events[0].level == Level.ERROR
        events[0].formattedMessage.contains('TASK-9')

        cleanup:
        logs.detach()
    }
}
