package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Sanity spec for the engine executor, check-runner, external-client and
 * judge-voter fakes: exercises their scripting, recording and throw-mode
 * behavior so the fakes are proven to compile and work for the section 4–7
 * orchestration specs. These are test fakes for the add-stage-engine ports,
 * not production code.
 */
class FakesSanitySpec extends Specification {

    private static final TaskContext CTX = new TaskContext('TASK-1', 't', 'b', [])
    private static final FakeWorkspace WS = new FakeWorkspace()

    private static ToolTrace trace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    }

    private static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), trace())
    }

    private static StageExecutor.Request request(int attempt) {
        new StageExecutor.Request(CTX, null, WS, attempt, [])
    }

    def "ScriptedExecutor returns queued results in order and records each request"() {
        given: 'an executor scripted with two results'
        def r0 = completed()
        def r1 = new ExecutionResult.DecisionNeeded('Q?', [], ExecutorUsage.none(), trace())
        def executor = new ScriptedExecutor([r0, r1])

        when: 'two rounds are executed'
        def out0 = executor.execute(request(0))
        def out1 = executor.execute(request(1))

        then: 'the results come back in order and both requests are recorded'
        out0 == r0
        out1 == r1
        executor.requests*.attempt() == [0, 1]
    }

    def "ScriptedExecutor fails loudly when its script is exhausted"() {
        when: 'more rounds are executed than scripted'
        new ScriptedExecutor([]).execute(request(0))

        then: 'the exhaustion is reported'
        thrown(IllegalStateException)
    }

    def "a scripted runner in throw-mode throws the supplied exception"() {
        given: 'a builtin runner set to throw'
        def boom = new RuntimeException('adapter blew up')
        def runner = new ScriptedBuiltinCheckRunner()
        runner.toThrow = boom

        when: 'it is run'
        runner.run(null, WS)

        then: 'the supplied exception propagates and the call is recorded'
        def thrown = thrown(RuntimeException)
        thrown.is(boom)
        runner.calls.size() == 1
    }

    def "a scripted runner runs its onRun side effect mid-call before returning"() {
        given: 'a command runner whose onRun advances a shared clock'
        def clock = new VirtualClock()
        def runner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        runner.onRun = { check, ws -> clock.advance(Duration.ofSeconds(7)) }

        when: 'it is run'
        def verdict = runner.run(null, WS)

        then: 'the side effect fired and the scripted verdict still came back'
        verdict instanceof Verdict.Pass
        clock.now() == Instant.EPOCH.plusSeconds(7)
        runner.calls.size() == 1
    }

    def "ScriptedExternalCheckClient returns a poll sequence and counts polls"() {
        given: 'a client scripted Running then Pass'
        def client = new ScriptedExternalCheckClient([
            new PollStatus.Running(),
            new PollStatus.Pass()
        ])

        when: 'polled twice'
        def first = client.poll(null, WS)
        def second = client.poll(null, WS)

        then: 'the sequence and count are as scripted'
        first instanceof PollStatus.Running
        second instanceof PollStatus.Pass
        client.pollCount == 2
    }

    def "ScriptedJudgeVoter returns a vote sequence, counts votes and records the context"() {
        given: 'a voter scripted with two votes'
        def voter = new ScriptedJudgeVoter([
            new JudgeVoter.Vote(new Verdict.Pass(), ['model-a': new TokenUsage(1, 2, 0, 0)]),
            new JudgeVoter.Vote(new Verdict.Fail([]), [:]),
        ])

        when: 'voted twice'
        def v0 = voter.vote(null, CTX, WS)
        def v1 = voter.vote(null, CTX, WS)

        then: 'the votes come back in order, the count is tracked and the context is recorded (FR7)'
        v0.verdict() instanceof Verdict.Pass
        v1.verdict() instanceof Verdict.Fail
        voter.voteCount == 2
        voter.contexts.every { it.is(CTX) }
    }
}
