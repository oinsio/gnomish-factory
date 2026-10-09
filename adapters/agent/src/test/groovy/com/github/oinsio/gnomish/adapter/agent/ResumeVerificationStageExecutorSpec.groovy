package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * FR21, D15 of add-sandbox-core (the integration pass): an interrupted
 * verification found on resume is consumed by the first matching round — the
 * interrupted round is restored into the run's cell and the agent is never re-run; every
 * other request delegates, and the pending state is consumed exactly once.
 *
 * <p>FR15, NFR-R4 of make-checkpoint-gate-durable (design D10): a pending verification carrying the
 * request the snapshot's tree held re-raises it as DecisionNeeded through the shared tolerant
 * reader, with the same empty telemetry, and still never runs the agent.
 */
class ResumeVerificationStageExecutorSpec extends Specification {

    /** The token the interrupted round's snapshot subject recorded. */
    static final RoundToken RECORDED = RoundToken.of('0a1b2c3d')

    /** A round a live run already holds in its cell before any request arrives. */
    static final RoundToken LIVE = RoundToken.of('0f0f0f0f')

    private static PendingVerification pending(Optional<String> request = Optional.empty()) {
        new PendingVerification('abc123', 'work', 2, RECORDED, request)
    }

    private static StageExecutor.Request request(String stageName, int attempt) {
        new StageExecutor.Request(
                new TaskContext('T-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                new StageDefinition(
                        stageName, 'purpose', [], [],
                        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO),
                new FakeWorkspace(),
                attempt,
                [])
    }

    private static ExecutionResult completed() {
        new ExecutionResult.Completed(
                new ExecutorUsage(Duration.ZERO, [], [:]),
                new ToolTrace(
                        new AttemptKey('T-1', 'work', 1), []), [])
    }

    def "FR21, FR13: the matching round skips the agent, restores the recorded round, and completes with empty telemetry"() {
        given:
        def delegate = Mock(StageExecutor)
        def ref = new CurrentRound()
        def executor = new ResumeVerificationStageExecutor(
                delegate, ref, pending())

        when:
        def result = executor.execute(request('work', 2))

        then: 'no delegation, the pending snapshot becomes the round result'
        0 * delegate.execute(_)
        result instanceof ExecutionResult.Completed

        and: "the cell holds the recorded round: the snapshot subject's token and the snapshot commit"
        ref.closed() == new ClosedRound(RECORDED, 'abc123')
        ref.opened() == RECORDED
        (result as ExecutionResult.Completed).trace() == new ToolTrace(new AttemptKey('T-1', 'work', 2), [])
        (result as ExecutionResult.Completed).usage() == new ExecutorUsage(Duration.ZERO, [], [:])
        (result as ExecutionResult.Completed).denials().isEmpty()
    }

    def "the pending verification is consumed exactly once — the next matching request delegates"() {
        given:
        def delegate = Mock(StageExecutor)
        def executor = new ResumeVerificationStageExecutor(
                delegate, new CurrentRound(), pending())
        executor.execute(request('work', 2))
        def delegateResult = completed()

        when:
        def result = executor.execute(request('work', 2))

        then:
        1 * delegate.execute(_) >> delegateResult

        and: 'the delegate result is returned as-is, not swallowed'
        result.is(delegateResult)
    }

    def "a non-matching stage or attempt delegates untouched, returning the delegate's exact result"() {
        given: 'a cell holding the live round the delegate is about to run'
        def delegate = Mock(StageExecutor)
        def ref = new CurrentRound()
        ref.open(LIVE)
        def executor = new ResumeVerificationStageExecutor(
                delegate, ref, pending())
        def delegateResult = completed()

        when:
        def result = executor.execute(request(stageName, attempt))

        then:
        1 * delegate.execute(_) >> delegateResult
        result.is(delegateResult)

        and: 'FR13: the recorded round was not restored over the live one'
        ref.opened() == LIVE

        when: 'the live round has no snapshot, so it is not closed — no recorded snapshot leaked in'
        ref.closed()

        then:
        thrown(IllegalStateException)

        where:
        stageName | attempt
        'other' | 2
        'work' | 3
    }

    def "a null pending verification is a pure pass-through, returning the delegate's exact result"() {
        given:
        def delegate = Mock(StageExecutor)
        def executor = new ResumeVerificationStageExecutor(delegate, new CurrentRound(), null)
        def delegateResult = completed()

        when:
        def result = executor.execute(request('work', 1))

        then:
        1 * delegate.execute(_) >> delegateResult
        result.is(delegateResult)
    }

    def "FR15: a pending verification carrying a request re-raises its question with empty telemetry, no agent round"() {
        given:
        def delegate = Mock(StageExecutor)
        def ref = new CurrentRound()
        def executor = new ResumeVerificationStageExecutor(delegate, ref, pending(
                        Optional.of('{"question":"which db?","options":["pg","sqlite"]}')))

        when:
        def result = executor.execute(request('work', 2))

        then: "the agent never runs, and the cell holds the recorded round"
        0 * delegate.execute(_)
        ref.closed() == new ClosedRound(RECORDED, 'abc123')

        and: "the snapshot's question, mapped through the same reader a live round uses"
        def needed = result as ExecutionResult.DecisionNeeded
        needed.question() == UntrustedText.agent('which db?')
        needed.options() == [
            UntrustedText.agent('pg'),
            UntrustedText.agent('sqlite')
        ]

        and: "the same empty telemetry as the Completed resume: the round's own died with its state commit"
        needed.usage() == new ExecutorUsage(Duration.ZERO, [], [:])
        needed.trace() == new ToolTrace(new AttemptKey('T-1', 'work', 2), [])
        needed.denials().isEmpty()
    }

    def "FR15: an unparseable request still re-raises, its raw content as the question"() {
        given:
        def executor = new ResumeVerificationStageExecutor(
                Mock(StageExecutor), new CurrentRound(), pending(Optional.of('not json')))

        when:
        def result = executor.execute(request('work', 2))

        then:
        (result as ExecutionResult.DecisionNeeded).question() == UntrustedText.agent('not json')
        (result as ExecutionResult.DecisionNeeded).options().isEmpty()
    }
}
