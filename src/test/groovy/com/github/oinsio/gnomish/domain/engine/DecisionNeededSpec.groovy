package com.github.oinsio.gnomish.domain.engine

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
import spock.lang.Specification

/**
 * StageAttemptLoop, task 5.6 — the gnome-initiated decision escalation path. A round
 * whose executor returns {@code ExecutionResult.DecisionNeeded(question, options, ...)}
 * escalates immediately as {@code Escalated(EscalationReport.DecisionNeeded)} carrying
 * the question and options verbatim, and does NOT burn a stage attempt: the round is
 * recorded (with the executor's usage and no check results) and persisted like any
 * other round, but {@code attemptsUsed} is unchanged (FR6, design D6). No verify chain
 * runs on such a round — the executor handed control back before any check.
 *
 * <p>Implements FR6, FR11, FR13 of add-stage-engine.
 */
class DecisionNeededSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def commandRunner = new ScriptedCommandCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def judgeVoter = new ScriptedJudgeVoter()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, clock, sleeper)
    }

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, int attemptLimit, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.toList())
    }

    static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static ExecutionResult.DecisionNeeded decisionNeeded(String question, List<String> options) {
        new ExecutionResult.DecisionNeeded(
                question, options, ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    // FR6: an executor DecisionNeeded escalates as Escalated(DecisionNeeded) carrying the
    //     question and options verbatim, WITHOUT burning an attempt (attemptsUsed unchanged),
    //     while the round IS recorded (its executor usage, no check results — FR13).
    def "a DecisionNeeded round escalates without burning an attempt and records the round"() {
        given: 'a stage whose executor asks a human instead of completing'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        def usage = ExecutorUsage.none()
        def trace = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
        executor.scripted << new ExecutionResult.DecisionNeeded('which db?', ['postgres', 'mysql'], usage, trace)

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(DecisionNeeded) carrying the exact question and options'
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report()
        report instanceof EscalationReport.DecisionNeeded
        (report as EscalationReport.DecisionNeeded).question() == 'which db?'
        (report as EscalationReport.DecisionNeeded).options() == ['postgres', 'mysql']

        and: 'no attempt was burned and exactly one round was recorded'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().size() == 1

        and: 'the recorded round carries the executor usage with empty check results (FR13)'
        def round = finalState.attempts()[0]
        round.round() == 0
        round.executorUsage().is(usage)
        round.checkResults().isEmpty()

        and: 'the round result is explicitly DECISION_NEEDED, not inferred from the empty check list (FR13, D5)'
        // FR13 "Round result is explicit": a round ending in DecisionNeeded before any check runs
        // carries the DECISION_NEEDED result rather than leaving consumers to infer it from an empty list.
        round.result() == AttemptRecord.Result.DECISION_NEEDED

        and: 'no verify check runner was invoked — the executor handed control back before any check'
        builtinRunner.calls.isEmpty()
    }

    // FR6/FR11: the DecisionNeeded round IS persisted like any other round — persisted once with
    //     the final state — and AttemptStarted + AttemptFinished were emitted for it.
    def "the DecisionNeeded round is persisted and emits AttemptStarted and AttemptFinished"() {
        given: 'a stage whose executor asks a human'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.scripted << decisionNeeded('which db?', ['postgres', 'mysql'])

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the round was persisted exactly once with the final state'
        persistence.entries.size() == 1
        persistence.entries[0].taskId == 'TASK-1'
        persistence.entries[0].state.is(outcome.finalState())

        and: 'both AttemptStarted and AttemptFinished were emitted for the round'
        listener.events.findAll { it instanceof EngineEvent.AttemptStarted }.size() == 1
        listener.events.findAll { it instanceof EngineEvent.AttemptFinished }.size() == 1
    }

    // FR11/NFR-O1: a DecisionNeeded round whose persist throws aborts the run — the decision
    //     round is recorded but its persist fails, so the outcome is Aborted(failedAt, cause)
    //     with the decision round's key and no AttemptFinished for it (the abort short-circuits
    //     before the AttemptFinished event and before the DecisionNeeded escalation).
    def "a DecisionNeeded round whose persist fails aborts the run"() {
        given: 'a stage whose executor asks a human, with persistence set to throw on that round'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.scripted << decisionNeeded('which db?', ['postgres', 'mysql'])
        persistence.failOnCall = 1

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Aborted at the decision round key, not an escalation'
        outcome instanceof TaskOutcome.Aborted
        def aborted = outcome as TaskOutcome.Aborted
        aborted.failedAt() == new AttemptKey('TASK-1', 'build', 0)
        !aborted.cause().isBlank()

        and: 'the decision round is recorded in the aborted in-memory state, attempt not burned'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().size() == 1
        finalState.attempts()[0].checkResults().isEmpty()

        and: 'no AttemptFinished was emitted for the round whose persist failed'
        listener.events.findAll { it instanceof EngineEvent.AttemptStarted }.size() == 1
        listener.events.findAll { it instanceof EngineEvent.AttemptFinished }.isEmpty()
    }

    // FR6: a DecisionNeeded after a prior quality failure still does NOT burn an attempt — the
    //     Fail burned attempt 0, the decision is round 1 and unburned, and its feedback carries
    //     the prior failure's findings forward (FR4).
    def "a DecisionNeeded after a quality failure carries prior findings and stays unburned"() {
        given: 'a stage that fails once then asks a human'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        def failA = fail('findingA')
        builtinRunner.scripted << failA
        executor.scripted << completed()
        executor.scripted << decisionNeeded('which db?', ['postgres', 'mysql'])

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(DecisionNeeded)'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.DecisionNeeded

        and: 'only the Fail burned an attempt — the decision round did not'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 1
        finalState.attempts().size() == 2

        and: 'the decision is round 1 with empty check results'
        finalState.attempts()[1].round() == 1
        finalState.attempts()[1].checkResults().isEmpty()

        and: 'the decision round\'s executor request carried the prior failure\'s findings as feedback'
        executor.requests.size() == 2
        def feedback = executor.requests[1].feedback()
        feedback.size() == 1
        feedback[0].verdict().is(failA)
    }
}
