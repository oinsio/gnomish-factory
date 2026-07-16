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
 * StageAttemptLoop, task 5.5 — the AttemptsExhausted terminal reached mid-run. When a
 * {@code Verdict.Fail} round burns the attempt that reaches the stage's resolved
 * attempt limit, the run escalates as {@code Escalated(AttemptsExhausted(limit))}
 * carrying the FULL recorded attempt history — every failed round of the current stage
 * (FR5 — the "Limit reached mid-run" scenario). Below the limit the loop retries as
 * before; this spec exercises only the exhaustion boundary and the self-describing
 * escalation it produces (UX1 — the "Report is self-describing" scenario).
 *
 * <p>Implements FR5, UX1 of add-stage-engine.
 */
class AttemptsExhaustedSpec extends Specification {

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

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    // FR5 (limit reached mid-run): with attempt limit 2, a check that Fails on every round
    //     escalates as Escalated(AttemptsExhausted(2)) after the 2nd quality failure — the
    //     executor is invoked exactly twice, no 3rd attempt runs after exhaustion, and both
    //     failed rounds are recorded.
    def "the last permitted quality failure escalates as AttemptsExhausted with the full history"() {
        given: 'a 2-attempt stage whose check fails on every round'
        def stageDef = stage('build', 2, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << fail('findingB')
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(AttemptsExhausted) carrying the resolved limit'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.AttemptsExhausted
        outcome.report().limit() == 2

        and: 'both attempts were burned and both failed rounds recorded'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 2
        finalState.attempts().size() == 2

        and: 'the executor was invoked exactly twice — no 3rd attempt after exhaustion'
        executor.requests.size() == 2
    }

    // FR5 (history complete): the final state lists EVERY recorded round with its findings —
    //     attempt 0's and attempt 1's findings are reconstructable from finalState.attempts() alone.
    def "the final state carries the findings of every recorded round"() {
        given: 'a 2-attempt stage failing with distinct findings each round'
        def stageDef = stage('build', 2, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << fail('findingB')
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'each recorded round carries its own findings, reconstructed from attempts() alone'
        def attempts = outcome.finalState().attempts()
        attempts.size() == 2
        findingMessages(attempts[0]) == ['findingA']
        findingMessages(attempts[1]) == ['findingB']

        and: 'the rounds are numbered 0 and 1 in order'
        attempts[0].round() == 0
        attempts[1].round() == 1
    }

    // UX1 (report is self-describing): using ONLY the outcome value and its finalState() — no
    //     logs, no other source — the escalation renders the stage, the limit, and every round's
    //     check results and findings.
    def "the escalation is renderable from the outcome and its final state alone"() {
        given: 'a 2-attempt stage failing with distinct findings each round'
        def stageDef = stage('build', 2, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << fail('findingB')
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven and rendered from the outcome alone'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())
        def summary = render(outcome)

        then: 'the summary names the stage, the limit and a finding from each attempt'
        summary.contains('build')
        summary.contains('limit=2')
        summary.contains('findingA')
        summary.contains('findingB')
    }

    /** The finding messages of a recorded round, flattened from its check results. */
    static List<String> findingMessages(AttemptRecord round) {
        round.checkResults()
                .collect { it.verdict() }
                .findAll { it instanceof Verdict.Fail }
                .collectMany { it.findings() }
                .collect { it.message() }
    }

    /**
     * Builds a human-readable escalation summary from ONLY the outcome and its final state —
     * no logs, no other source (UX1). Renders the stage name (from the position), the limit
     * (from the report) and every recorded round's findings.
     */
    static String render(TaskOutcome outcome) {
        def escalated = outcome as TaskOutcome.Escalated
        def report = escalated.report() as EscalationReport.AttemptsExhausted
        def state = escalated.finalState()
        def stageName = (state.position() as Position.AtStage).name()
        def rounds = state.attempts()
                .collect { "round ${it.round()}: ${findingMessages(it).join(', ')}" }
                .join('; ')
        "stage=${stageName} limit=${report.limit()} attemptsUsed=${state.attemptsUsed()} [${rounds}]"
    }
}
