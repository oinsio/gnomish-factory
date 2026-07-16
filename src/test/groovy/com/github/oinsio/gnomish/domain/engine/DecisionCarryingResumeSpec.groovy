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
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Instant
import spock.lang.Specification

/**
 * Decision-carrying resume, task 5.10 (FR7) — human decisions are pass-through context,
 * never commands (design D6). This spec proves, entirely through {@code Engine.run}, that
 * the two caller-side resume adjustments the design names — a decision appended to
 * {@code TaskContext.decisions} and a reset attempt counter — reach the run verbatim, and
 * that the engine itself never interprets, reorders, drops, mutates, or resets any of it:
 *
 * <ul>
 *   <li>the full decisions list — including one the caller freshly appended to answer an
 *       earlier {@code DecisionNeeded} — reaches the executor request byte-for-byte and in
 *       chronological order, with execution proceeding from the recorded stage (FR7);</li>
 *   <li>the counter reset is the CALLER's state manipulation, not the engine's: a state
 *       that would escalate {@code AttemptsExhausted} on entry does so, while the same run
 *       supplied a caller-reset counter proceeds and executes instead (design D6);</li>
 *   <li>the same decisions also reach a judge vote on the run (FR7) — the judge sees the
 *       identical context the executor did.</li>
 * </ul>
 *
 * <p>Implements FR7 of add-stage-engine.
 */
class DecisionCarryingResumeSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()

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

    // FR7: a TaskContext whose decisions list carries several decisions — including one the
    //      caller freshly appended to answer an earlier DecisionNeeded — reaches the executor
    //      request VERBATIM: same list, same chronological order, byte-for-byte, nothing
    //      reordered, dropped, or mutated; and execution proceeds from the RECORDED stage.
    def "the full decisions list reaches the executor request verbatim and from the recorded stage"() {
        given: 'a context whose decisions carry two prior answers plus one the caller just appended'
        def older = new Decision('use postgres', 'design', 'alice', Instant.parse('2026-07-10T00:00:00Z'))
        def middle = new Decision('single region', null, null, null)
        def appended = new Decision('yes, add the migration', 'build', 'bob', Instant.parse('2026-07-16T00:00:00Z'))
        def decisions = [older, middle, appended]
        def context = new TaskContext('TASK-1', 'title', 'body', decisions)
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed()

        when: 'the run resumes from a state positioned AT the recorded stage'
        def outcome = new Engine().run(pipeline(stageDef), context, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the run proceeded and executed exactly one round'
        outcome instanceof TaskOutcome.Completed
        executor.requests.size() == 1

        and: 'the request carried the SAME decisions list, same order, byte-for-byte — the appended one included'
        def carried = executor.requests[0].context().decisions()
        carried == decisions
        carried == [older, middle, appended]

        and: 'the engine neither reordered, dropped, nor mutated any decision'
        carried.size() == 3
        carried[0].is(older)
        carried[1].is(middle)
        carried[2].is(appended)

        and: 'execution proceeded from the recorded stage'
        executor.requests[0].stage().name() == 'build'
    }

    // FR7 / design D6: the ENGINE never resets the attempt counter — the caller does. A state
    //      that WOULD escalate AttemptsExhausted on entry (attemptsUsed == limit) does exactly
    //      that; the same run supplied a caller-reset counter (below the limit) proceeds and
    //      executes instead. The engine acts on the caller's state manipulation, never its own.
    def "a caller-reset counter is what lets the run proceed instead of escalating"() {
        given: 'a 2-attempt stage and a context carrying one decision'
        def decision = new Decision('proceed', null, null, null)
        def context = new TaskContext('TASK-1', 'title', 'body', [decision])
        def stageDef = stage('build', 2, [builtin('files_exist')])

        when: 'a run enters at the limit — no caller reset — with its own fakes'
        def exhaustedExecutor = new ScriptedExecutor()
        def exhaustedPorts = new EnginePorts(exhaustedExecutor, new ScriptedBuiltinCheckRunner(),
                new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(),
                new RecordingEventListener(), new InMemoryAttemptPersistence(), clock, sleeper)
        def exhausted = new Engine().run(pipeline(stageDef), context,
                new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none()), WORKSPACE, exhaustedPorts)

        then: 'without the reset the engine escalates AttemptsExhausted and never executes'
        exhausted instanceof TaskOutcome.Escalated
        exhausted.report() instanceof EscalationReport.AttemptsExhausted
        exhaustedExecutor.requests.isEmpty()

        when: 'the caller instead supplies a state with the counter reset below the limit'
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed()
        def resumed = new Engine().run(pipeline(stageDef), context,
                TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the run now proceeds and the executor IS invoked — the caller reset, not the engine'
        resumed instanceof TaskOutcome.Completed
        executor.requests.size() == 1
        executor.requests[0].context().decisions() == [decision]
    }

    // FR7: the same decisions also reach the judge vote on the run — the judge sees the
    //      identical context the executor did (task 4.4 covers judge threading in depth;
    //      this asserts it on the same decision-carrying resume).
    def "the same decisions reach the judge vote on the run"() {
        given: 'a context with a decision and a single-vote judge check that passes'
        def decision = new Decision('accept the tradeoff', 'review', 'carol', null)
        def context = new TaskContext('TASK-1', 'title', 'body', [decision])
        def judge = new VerifyCheck.Judge('criteria.md', 'judge-model', [:], 1)
        def stageDef = stage('review', 5, [judge])
        judgeVoter.scripted << new JudgeVoter.Vote(new Verdict.Pass(), null)
        executor.scripted << completed()

        when: 'the run resumes at the recorded stage'
        def outcome = new Engine().run(pipeline(stageDef), context, TaskState.atStageStart('review'), WORKSPACE, ports())

        then: 'the run completed and cast exactly one vote'
        outcome instanceof TaskOutcome.Completed
        judgeVoter.voteCount == 1

        and: 'the judge received the same decisions as the executor, byte-for-byte'
        judgeVoter.contexts[0].decisions() == [decision]
        judgeVoter.contexts[0].decisions() == executor.requests[0].context().decisions()
    }
}
