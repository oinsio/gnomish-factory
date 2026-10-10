package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.console.DialogConsole
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.status.ReportPlane
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR9, D8 of add-manual-run; FR1, FR2, FR6, FR7 of make-run-headless: the outcome dispatch —
 * exhaustive {@link TaskOutcome} switch, the {@code PipelineMismatch} internal-error special case,
 * and the stops: {@code Escalated} and {@code Paused} print their render and are returned to the
 * terminal boundary; the console is never read.
 */
class RunnerOutcomeLoopSpec extends Specification implements StdoutCaptureFixture {

    private static final TaskState STATE = TaskState.atStageStart('build')
    private static final TaskContext CONTEXT = new TaskContext('task-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    private static final TerminalOutcomeRender.ReturnPath RETURN_PATH =
    new TerminalOutcomeRender.ReturnPath(Path.of('/work/clone'), 'task-1')

    /** A console whose reader fails the spec: FR6, no path of the loop may read stdin. */
    private ScriptedConsoleIO io = new ScriptedConsoleIO() {
        @Override
        String readLine() {
            throw new AssertionError('the outcome loop read the console')
        }
    }
    private RunnerOutcomeLoop loop = new RunnerOutcomeLoop(new Engine(), new DialogConsole(io), liveErrorConsole())

    /** A one-attempt stage with a single builtin check — the shape every {@code run} spec here drives. */
    private static StageDefinition oneCheckStage(String name, AdvancementMode advancement) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), advancement)
    }

    private static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('task-1', 'build', 0), []), [])
    }

    private static EnginePorts ports(ScriptedExecutor executor, List<Verdict> verdicts, InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()) {
        def clock = new VirtualClock()
        new EnginePorts(executor, new ScriptedBuiltinCheckRunner(verdicts), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                persistence, VirtualTimeEquipment.on(clock))
    }

    def "dispatch returns Completed and prints a final status summary"() {
        given:
        def outcome = new TaskOutcome.Completed(STATE)

        expect:
        loop.dispatch(CONTEXT, outcome, RETURN_PATH).is(outcome)

        and: 'a final status summary was printed, naming the task'
        io.printed.any { it.contains(CONTEXT.taskId()) }
    }

    def "FR2: dispatch returns a Paused stop after printing the checkpoint line and the return path"() {
        given: 'a state already advanced past the passed stage'
        def outcome = new TaskOutcome.Paused(new TaskState(new Position.AtStage('deploy'), 2, [], ExecutorUsage.none()), 'build')

        expect:
        loop.dispatch(CONTEXT, outcome, RETURN_PATH).is(outcome)

        and: 'exactly the stop render, on one print, and no prompt'
        io.printed == [
            TerminalOutcomeRender.paused('build', RETURN_PATH) + ConsoleIO.LINE_END
        ]
    }

    def "FR1: dispatch returns an Escalated stop after printing the report and the return path"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        def outcome = new TaskOutcome.Escalated(new TaskState(new Position.AtStage('build'), 3, [], ExecutorUsage.none()), report)

        expect:
        loop.dispatch(CONTEXT, outcome, RETURN_PATH).is(outcome)

        and:
        io.printed == [
            TerminalOutcomeRender.escalated(report, RETURN_PATH) + ConsoleIO.LINE_END
        ]
    }

    def "an in-place stop prints its render without a return path"() {
        given:
        def outcome = new TaskOutcome.Paused(STATE, 'build')

        when:
        loop.dispatch(CONTEXT, outcome, null)

        then:
        io.printed == [
            "Stage 'build' passed. Awaiting approval." + ConsoleIO.LINE_END
        ]
    }

    def "dispatch throws InternalErrorException carrying the console render for PipelineMismatch, printing nothing"() {
        given:
        def report = new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('\u001B[31mstale-stage @team #12'))

        when:
        loop.dispatch(CONTEXT, new TaskOutcome.Escalated(STATE, report), RETURN_PATH)

        then:
        def ex = thrown(InternalErrorException)
        ex.message == TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE)
        ex.message.contains('^[[31m')
        !ex.message.contains('~~~~')

        and:
        io.printed.isEmpty()
    }

    def "dispatch throws AbortedException carrying the full outcome after reporting it to stderr only"() {
        given: 'a burned-attempts state that never reached durable storage'
        def unpersistedState = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())
        def outcome = new TaskOutcome.Aborted(unpersistedState, new AttemptKey('task-1', 'build', 2), UntrustedText.subprocess('connection reset by peer'))

        and: 'stderr is captured for the duration of this test only'
        def capturedErr = new ByteArrayOutputStream()
        def originalErr = System.err
        System.err = new PrintStream(capturedErr)

        when:
        loop.dispatch(CONTEXT, outcome, RETURN_PATH)

        then:
        def ex = thrown(AbortedException)
        ex.message == 'connection reset by peer'
        ex.outcome() == outcome

        and: 'the cause and the unpersisted-state summary went to stderr'
        def output = capturedErr.toString()
        output.contains('connection reset by peer')
        output.contains("Task 'task-1': the round at stage 'build', attempt 2 was not persisted")

        and:
        io.printed.isEmpty()

        cleanup:
        System.err = originalErr
    }

    def "FR1, FR7: run returns the escalation a real engine stopped on, without reading the console"() {
        given: 'a one-attempt stage whose single builtin check fails'
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [
            oneCheckStage('build', AdvancementMode.AUTO)
        ])
        def executor = new ScriptedExecutor([completed()])

        when:
        def outcome = loop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports(executor, [new Verdict.Fail([])]), RETURN_PATH)

        then: 'one round, no loop-back'
        executor.requests.size() == 1
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() == new EscalationReport.AttemptsExhausted(1)

        and:
        io.printed.last().contains('--resume=task-1 [--decision="..."]')
    }

    def "FR2, FR7: run returns the checkpoint a real engine paused at, without continuing to the next stage"() {
        given: 'a manual-advancement first stage that passes, followed by an auto second stage'
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [
            oneCheckStage('build', AdvancementMode.MANUAL),
            oneCheckStage('deploy', AdvancementMode.AUTO)
        ])
        def executor = new ScriptedExecutor([completed(), completed()])

        when:
        def outcome = loop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports(executor, [
            new Verdict.Pass(),
            new Verdict.Pass()
        ]), RETURN_PATH)

        then: 'only the checkpointed stage ran'
        executor.requests*.stage()*.name() == ['build']
        outcome instanceof TaskOutcome.Paused
        (outcome as TaskOutcome.Paused).passedStage() == 'build'
        // FR1 of make-checkpoint-gate-durable: the pause holds the task at the gate, not past it.
        (outcome as TaskOutcome.Paused).finalState().position() == new Position.AwaitingApproval('build')
    }

    def "run returns Completed when the pipeline reaches its end"() {
        given:
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [
            oneCheckStage('build', AdvancementMode.AUTO)
        ])

        expect:
        loop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports(new ScriptedExecutor([completed()]), [new Verdict.Pass()]), RETURN_PATH) instanceof TaskOutcome.Completed
    }

    def "run reports to stderr and stops after a breaking persistence fake aborts the engine"() {
        given: 'a persistence port that throws on its first call'
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [
            oneCheckStage('build', AdvancementMode.AUTO)
        ])
        def executor = new ScriptedExecutor([completed()])
        def capturedErr = new ByteArrayOutputStream()
        def originalErr = System.err
        System.err = new PrintStream(capturedErr)

        when:
        loop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(),
                ports(executor, [new Verdict.Pass()], new InMemoryAttemptPersistence(failOnCall: 1)), RETURN_PATH)

        then:
        thrown(AbortedException)
        executor.requests.size() == 1
        capturedErr.toString().contains('persist failed on call 1')
        io.printed.isEmpty()

        cleanup:
        System.err = originalErr
    }
}
