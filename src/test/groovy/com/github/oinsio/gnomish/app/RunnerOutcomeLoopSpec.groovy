package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException
import com.github.oinsio.gnomish.adapter.console.DialogConsole
import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
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
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * FR9, D8 of add-manual-run: the outcome loop — exhaustive {@link TaskOutcome} dispatch, typed
 * per-kind {@link EscalationReport} renders, the {@code PipelineMismatch} internal-error special
 * case, and (task 7.5) the real resumable-escalation dialog: decision prompt, reset {@code
 * attemptsUsed}, preserved {@code totals}, optional appended {@link Decision}, loop-back into
 * the engine. {@code Paused}/{@code Aborted} stay stubs for later tasks.
 */
class RunnerOutcomeLoopSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')
    private static final TaskContext CONTEXT = new TaskContext('task-1', 'title', 'body', [])
    private static final Clock CLOCK = Clock.fixed(Instant.parse('2026-07-17T10:00:00Z'), ZoneOffset.UTC)

    private ScriptedConsoleIO io = new ScriptedConsoleIO()
    private DialogConsole console = new DialogConsole(io, { json -> 'unused' })
    private RunnerOutcomeLoop loop = new RunnerOutcomeLoop(new Engine(), console, CLOCK)

    private static DialogConsole consoleWithScript(List<String> script) {
        new DialogConsole(new ScriptedConsoleIO(script), { json -> 'unused' })
    }

    def "dispatch routes Completed without throwing and prints a final status summary"() {
        given:
        def outcome = new TaskOutcome.Completed(STATE)

        when:
        loop.dispatch(CONTEXT, outcome)

        then:
        noExceptionThrown()

        and: 'a final status summary was printed, naming the task'
        io.printed.any { it.contains(CONTEXT.taskId()) }
    }

    def "dispatch resumes a Paused checkpoint with confirmation only — no reset, no decision"() {
        given: 'a burned-attempts, cumulative-totals state already advanced past the passed stage'
        def totals = ExecutorUsage.none()
        def advancedState = new TaskState(new Position.AtStage('deploy'), 2, [], totals)
        def outcome = new TaskOutcome.Paused(advancedState, 'build')
        def scriptedIo = new ScriptedConsoleIO([''])
        def scriptedConsole = new DialogConsole(scriptedIo, { json -> 'unused' })
        def scriptedLoop = new RunnerOutcomeLoop(new Engine(), scriptedConsole, CLOCK)

        when:
        def resumption = scriptedLoop.dispatch(CONTEXT, outcome)

        then: 'the checkpoint message names the stage that passed'
        scriptedIo.printed.any { it.contains('build') && it.contains('checkpoint') }

        and: 'exactly one prompt line was printed'
        scriptedIo.printed.count { it.contains('Press Enter') } == 1

        and: 'the returned context is the same instance — no decision added'
        resumption.context().is(CONTEXT)

        and: 'the returned state equals finalState exactly — nothing reset'
        resumption.state() == advancedState
        resumption.state().position() == new Position.AtStage('deploy')
        resumption.state().attemptsUsed() == 2
        resumption.state().totals() == totals
    }

    def "dispatch still issues the checkpoint prompt for Paused even when input was already exhausted (no pre-check on this branch, by design)"() {
        given: 'a console whose EOF-then-resume behavior is scripted directly: readLine throws once, then a fake operator plugs in more lines — modeling "input exhausted earlier, then a human takes over the terminal again"'
        def totals = ExecutorUsage.none()
        def advancedState = new TaskState(new Position.AtStage('deploy'), 0, [], totals)
        def outcome = new TaskOutcome.Paused(advancedState, 'build')
        def io = new ReExhaustibleConsoleIO()
        def consoleThatWasExhausted = new DialogConsole(io, { json -> 'unused' })
        try {
            consoleThatWasExhausted.prompt('earlier adapter prompt: ')
        } catch (ignored) {
            // latches inputExhausted() — mirrors Case 1 for Escalated
        }
        assert consoleThatWasExhausted.inputExhausted()
        io.printed.clear()
        io.resume([''])
        def loopUnderTest = new RunnerOutcomeLoop(new Engine(), consoleThatWasExhausted, CLOCK)

        when:
        def resumption = loopUnderTest.dispatch(CONTEXT, outcome)

        then: 'the checkpoint prompt was issued and answered — handlePaused never consults the latched inputExhausted() flag'
        io.printed.any { it.contains('Press Enter') }
        resumption != null
    }

    def "dispatch rethrows CheckpointEofException when the checkpoint prompt itself hits EOF (Case 2, deliberate Ctrl-D)"() {
        given: 'a console whose script runs out exactly at the checkpoint prompt'
        def totals = ExecutorUsage.none()
        def advancedState = new TaskState(new Position.AtStage('deploy'), 0, [], totals)
        def outcome = new TaskOutcome.Paused(advancedState, 'build')
        def io = new ScriptedConsoleIO([])
        def freshConsole = new DialogConsole(io, { json -> 'unused' })
        def freshLoop = new RunnerOutcomeLoop(new Engine(), freshConsole, CLOCK)

        when:
        freshLoop.dispatch(CONTEXT, outcome)

        then:
        def ex = thrown(CheckpointEofException)
        ex.cause instanceof ConsoleClosedException
    }

    def "dispatch throws AbortedException after reporting Aborted"() {
        given:
        def outcome = new TaskOutcome.Aborted(STATE, new AttemptKey('task-1', 'build', 1), 'persist failed')

        and: 'stderr is captured for the duration of this test only'
        def capturedErr = new ByteArrayOutputStream()
        def originalErr = System.err
        System.err = new PrintStream(capturedErr)

        when:
        loop.dispatch(CONTEXT, outcome)

        then:
        def ex = thrown(AbortedException)
        ex.message == 'persist failed'

        cleanup:
        System.err = originalErr
    }

    def "dispatch reports Aborted's cause and an unpersisted-state summary to stderr, then throws AbortedException instead of returning a resumption"() {
        given: 'a burned-attempts state that never reached durable storage'
        def totals = ExecutorUsage.none()
        def unpersistedState = new TaskState(new Position.AtStage('build'), 2, [], totals)
        def failedAt = new AttemptKey('task-1', 'build', 2)
        def outcome = new TaskOutcome.Aborted(unpersistedState, failedAt, 'connection reset by peer')

        and: 'stderr is captured for the duration of this test only'
        def capturedErr = new ByteArrayOutputStream()
        def originalErr = System.err
        System.err = new PrintStream(capturedErr)

        when:
        loop.dispatch(CONTEXT, outcome)

        then: 'the loop signals termination via a thrown AbortedException — no resumption'
        def ex = thrown(AbortedException)
        ex.message == 'connection reset by peer'

        and: 'the cause is printed verbatim to stderr'
        def output = capturedErr.toString()
        output.contains('connection reset by peer')

        and: 'the unpersisted-state summary names the task, the failed round, and the last known state'
        output.contains('task-1')
        output.contains('build')
        output.contains('2')

        and: 'nothing was printed to the dialog console'
        io.printed.isEmpty()

        cleanup:
        System.err = originalErr
    }

    def "dispatch routes a non-mismatch Escalated without throwing"() {
        given:
        def scriptedLoop = new RunnerOutcomeLoop(new Engine(), consoleWithScript(['']), CLOCK)
        def outcome = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))

        when:
        scriptedLoop.dispatch(CONTEXT, outcome)

        then:
        noExceptionThrown()
    }

    def "dispatch throws InternalErrorException carrying the rendered text for PipelineMismatch, without prompting"() {
        given:
        def report = new EscalationReport.PipelineMismatch('stale-stage')
        def outcome = new TaskOutcome.Escalated(STATE, report)

        when:
        loop.dispatch(CONTEXT, outcome)

        then:
        def ex = thrown(InternalErrorException)
        ex.message == loop.renderEscalation(report)
        ex.message.contains('stale-stage')

        and: 'no prompt was issued'
        io.printed.isEmpty()
    }

    def "dispatch resumes a decision-carrying escalation with attemptsUsed reset, totals preserved, and the decision appended"() {
        given: 'a state with burned attempts and cumulative totals, escalated with AttemptsExhausted'
        def totals = ExecutorUsage.none()
        def burnedState = new TaskState(new Position.AtStage('build'), 3, [], totals)
        def outcome = new TaskOutcome.Escalated(burnedState, new EscalationReport.AttemptsExhausted(3))
        def scriptedIo = new ScriptedConsoleIO(['fixed the environment'])
        def scriptedConsole = new DialogConsole(scriptedIo, { json -> 'unused' })
        def scriptedLoop = new RunnerOutcomeLoop(new Engine(), scriptedConsole, CLOCK)

        when:
        def resumption = scriptedLoop.dispatch(CONTEXT, outcome)

        then: 'the reset state keeps the same position, resets attemptsUsed, and preserves totals'
        resumption.state() == new TaskState(new Position.AtStage('build'), 0, [], totals)

        and: 'a new Decision is appended, authored by the operator, scoped to the current stage'
        resumption.context().decisions().size() == 1
        def decision = resumption.context().decisions()[0]
        decision.body() == 'fixed the environment'
        decision.author() == 'operator'
        decision.stage() == 'build'
        decision.time() == CLOCK.instant()

        and: 'the rest of the context is unchanged'
        resumption.context().taskId() == CONTEXT.taskId()
        resumption.context().title() == CONTEXT.title()
        resumption.context().body() == CONTEXT.body()

        and: 'the rendered escalation report was printed before the decision prompt'
        scriptedIo.printed.any { it == scriptedLoop.renderEscalation(outcome.report()) }
    }

    def "dispatch throws InputExhaustedException without prompting when the console's input is already exhausted (Case 1, FR13, NFR-R1, D2)"() {
        given: 'a console whose input already hit EOF on a prior prompt, deeper in the stack'
        def exhaustedIo = new ScriptedConsoleIO([])
        def exhaustedConsole = new DialogConsole(exhaustedIo, { json -> 'unused' })
        try {
            exhaustedConsole.prompt('earlier adapter prompt: ')
        } catch (ignored) {
            // expected — this is what latches inputExhausted() before the escalation is dispatched
        }
        assert exhaustedConsole.inputExhausted()
        exhaustedIo.printed.clear()
        def exhaustedLoop = new RunnerOutcomeLoop(new Engine(), exhaustedConsole, CLOCK)
        def outcome = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))

        when:
        exhaustedLoop.dispatch(CONTEXT, outcome)

        then:
        thrown(InputExhaustedException)

        and: 'no resume-dialog prompt was issued — the console was not touched again'
        exhaustedIo.printed.isEmpty()
    }

    def "dispatch still resumes normally through the decision prompt when input is not exhausted (regression)"() {
        given:
        def scriptedLoop = new RunnerOutcomeLoop(new Engine(), consoleWithScript(['']), CLOCK)
        def outcome = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))

        when:
        def resumption = scriptedLoop.dispatch(CONTEXT, outcome)

        then:
        resumption != null
    }

    def "dispatch rethrows EscalationEofException when the resume-prompt itself hits EOF (Case 2, deliberate Ctrl-D)"() {
        given: 'a console whose script runs out exactly at the resume-decision prompt'
        def io = new ScriptedConsoleIO([])
        def freshConsole = new DialogConsole(io, { json -> 'unused' })
        def freshLoop = new RunnerOutcomeLoop(new Engine(), freshConsole, CLOCK)
        def outcome = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))

        when:
        freshLoop.dispatch(CONTEXT, outcome)

        then: 'the exception is EscalationEofException, not the Case-1 InputExhaustedException'
        def ex = thrown(EscalationEofException)
        ex.cause instanceof ConsoleClosedException
    }

    def "dispatch resumes an empty-input escalation with attemptsUsed reset but no decision appended"() {
        given: 'a CannotVerify escalation answered with a bare Enter'
        def totals = ExecutorUsage.none()
        def burnedState = new TaskState(new Position.AtStage('build'), 1, [], totals)
        def report = new EscalationReport.CannotVerify(new CheckRef(0, 'command:./gradlew test'), 'timeout', 'trace')
        def outcome = new TaskOutcome.Escalated(burnedState, report)
        def scriptedLoop = new RunnerOutcomeLoop(new Engine(), consoleWithScript(['']), CLOCK)

        when:
        def resumption = scriptedLoop.dispatch(CONTEXT, outcome)

        then: 'attemptsUsed resets and totals are preserved, but no decision is appended'
        resumption.state() == new TaskState(new Position.AtStage('build'), 0, [], totals)
        resumption.context().decisions() == CONTEXT.decisions()
        resumption.context().decisions().isEmpty()
    }

    def "run loops back into a real engine with the resumed context/state after a decision-carrying escalation"() {
        given: 'a one-attempt stage whose single builtin check fails once, then passes on resume'
        def stageDef = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [stageDef])

        def executor = new ScriptedExecutor([
            completed(),
            completed(),
        ])
        def builtinRunner = new ScriptedBuiltinCheckRunner([
            new Verdict.Fail([]),
            new Verdict.Pass(),
        ])
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                new InMemoryAttemptPersistence(), clock, new VirtualSleeper(clock))

        def resumingLoop = new RunnerOutcomeLoop(
                new Engine(), consoleWithScript(['fixed the environment']), CLOCK)

        when:
        resumingLoop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports)

        then: 'the executor ran twice — the initial quality-failing attempt and the resumed one'
        executor.requests.size() == 2

        and: 'the resumed round is attempt 0 again — a fresh attempt-history window after reset'
        executor.requests[1].attempt() == 0

        and: 'the resumed round saw the appended operator decision'
        executor.requests[1].context().decisions().any {
            it.body() == 'fixed the environment' && it.author() == 'operator'
        }
    }

    def "run loops back into a real engine after a manual checkpoint, with position and counters untouched"() {
        given: 'a manual-advancement first stage that passes, followed by an auto second stage'
        def buildStage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.MANUAL)
        def deployStage = new StageDefinition('deploy', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [buildStage, deployStage])

        def executor = new ScriptedExecutor([
            completed(),
            completed(),
        ])
        def builtinRunner = new ScriptedBuiltinCheckRunner([
            new Verdict.Pass(),
            new Verdict.Pass(),
        ])
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                new InMemoryAttemptPersistence(), clock, new VirtualSleeper(clock))

        def scriptedIo = new ScriptedConsoleIO([''])
        def scriptedConsole = new DialogConsole(scriptedIo, { json -> 'unused' })
        def resumingLoop = new RunnerOutcomeLoop(new Engine(), scriptedConsole, CLOCK)

        when:
        resumingLoop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports)

        then: 'the executor ran twice — the manual-checkpointed build, then the resumed deploy'
        executor.requests.size() == 2
        executor.requests[0].stage().name() == 'build'
        executor.requests[1].stage().name() == 'deploy'

        and: 'the resumed round starts fresh — attempt 0, since deploy has never been attempted'
        executor.requests[1].attempt() == 0

        and: 'the checkpoint message named the stage that passed'
        scriptedIo.printed.any { it.contains('build') && it.contains('checkpoint') }
    }

    private static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('task-1', 'build', 0), []))
    }

    def "run reports to stderr and stops after a breaking persistence fake aborts the engine"() {
        given: 'a persistence port that throws on its first call'
        def stageDef = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        def pipeline = new PipelineDefinition('1', new AutonomyLimits(3), [stageDef])

        def executor = new ScriptedExecutor([completed()])
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def breakingPersistence = new InMemoryAttemptPersistence(failOnCall: 1)
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(),
                breakingPersistence, clock, new VirtualSleeper(clock))

        def capturedErr = new ByteArrayOutputStream()
        def originalErr = System.err
        System.err = new PrintStream(capturedErr)

        when:
        loop.run(pipeline, CONTEXT, STATE, new FakeWorkspace(), ports)

        then: 'run propagates AbortedException — no loop-back after the abort'
        thrown(AbortedException)

        and: 'the executor ran exactly once'
        executor.requests.size() == 1

        and: 'the failure and an unpersisted-state summary were printed to stderr'
        def output = capturedErr.toString()
        output.contains('task-1')
        output.contains('build')
        output.contains('persist failed on call 1')

        and: 'nothing was printed to the dialog console — this is a stderr-only report'
        io.printed.isEmpty()

        cleanup:
        System.err = originalErr
    }

    def "renderEscalation produces distinguishable, kind-specific text for #report.class.simpleName"() {
        expect:
        loop.renderEscalation(report).contains(expectedFragment)

        where:
        report                                                                                   | expectedFragment
        new EscalationReport.AttemptsExhausted(3)                                                 | '3'
        new EscalationReport.DecisionNeeded('proceed?', ['yes', 'no'])                            | 'proceed?'
        new EscalationReport.CannotVerify(new CheckRef(0, 'command:./gradlew test'), 'timeout', 'trace') | 'command:./gradlew test'
        new EscalationReport.PipelineMismatch('stale-stage')                                      | 'stale-stage'
        new EscalationReport.CannotExecute('agent crashed')                                       | 'agent crashed'
    }

    def "renderEscalation produces distinct text across all five report kinds"() {
        given:
        def reports = [
            new EscalationReport.AttemptsExhausted(3),
            new EscalationReport.DecisionNeeded('proceed?', ['yes', 'no']),
            new EscalationReport.CannotVerify(new CheckRef(0, 'command:./gradlew test'), 'timeout', 'trace'),
            new EscalationReport.PipelineMismatch('stale-stage'),
            new EscalationReport.CannotExecute('agent crashed'),
        ]

        when:
        def rendered = reports.collect { loop.renderEscalation(it) }

        then:
        rendered.toSet().size() == reports.size()
    }

    /**
     * A {@link com.github.oinsio.gnomish.adapter.console.ConsoleIO} fake that can hit EOF once
     * and then be handed more script via {@link #resume}, unlike {@link ScriptedConsoleIO} which
     * stays exhausted forever. Models "input exhausted earlier, then more lines become
     * available" purely to test that {@code handlePaused} never consults {@code
     * DialogConsole#inputExhausted()} — that flag stays latched {@code true} even once this fake
     * has more lines to give.
     */
    private static class ReExhaustibleConsoleIO implements com.github.oinsio.gnomish.adapter.console.ConsoleIO {
        private final List<String> script = []
        final List<String> printed = []

        void resume(List<String> lines) {
            script.addAll(lines)
        }

        @Override
        String readLine() {
            if (script.isEmpty()) {
                throw new com.github.oinsio.gnomish.adapter.console.ConsoleClosedException()
            }
            script.removeFirst()
        }

        @Override
        void print(String text) {
            printed << text
        }
    }
}
