package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AnswerFileRead
import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.executorcontract.ResultSource
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.time.Duration
import spock.lang.Specification

/**
 * FR21, D15 of add-sandbox-core (the integration pass): an interrupted
 * verification found on resume is consumed by the first matching round — the
 * interrupted round is restored into the run's cell and the agent is never re-run; every
 * other request delegates, and the pending state is consumed exactly once.
 *
 * <p>FR15, NFR-R4 of make-checkpoint-gate-durable (design D10): a pending verification carrying the
 * request the snapshot's tree held re-raises it as DecisionNeeded, with the same empty telemetry, and
 * still never runs the agent. FR21, NFR-R5 of extract-agent-round-runner (design D15): the reading
 * is the one answer read's, and a refused one is the failure the live round would have had.
 */
class ResumeVerificationStageExecutorSpec extends Specification {

    /** The token the interrupted round's snapshot subject recorded. */
    static final RoundToken RECORDED = RoundToken.of('0a1b2c3d')

    /** A round a live run already holds in its cell before any request arrives. */
    static final RoundToken LIVE = RoundToken.of('0f0f0f0f')

    /**
     * The pending verification of the interrupted round; {@code answer} is the answer file the
     * snapshot's tree held, read through the same {@link AnswerFileRead} the detector uses.
     */
    private static PendingVerification pending(String answer = null) {
        def read = answer == null ? new AnswerFileReading.Absent() : AnswerFileRead.read({ long limit ->
            Optional.of(answer.getBytes(StandardCharsets.UTF_8))
        } as ResultSource, UntrustedText.&agent)
        new PendingVerification('abc123', 'work', 2, RECORDED, read, 'abc123', ['abc123'])
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
        ExecutionResult.completed(
                new ExecutorUsage(Duration.ZERO, [], [:]),
                new ToolTrace(
                        new AttemptKey('T-1', 'work', 1), []), [])
    }

    // Scenario: executor/agent-executor / A snapshot without a request resumes as Completed
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
        (result.status() instanceof ExecutionResult.Completed && result.answer() == null)

        and: "the cell holds the recorded round: the snapshot subject's token and the snapshot commit"
        ref.closed() == new ClosedRound(RECORDED, 'abc123', 'abc123', RoundClose.VERIFIABLE)
        ref.opened() == RECORDED
        result.trace() == new ToolTrace(new AttemptKey('T-1', 'work', 2), [])
        result.usage() == new ExecutorUsage(Duration.ZERO, [], [:])
        result.denials().isEmpty()
    }

    // FR10, D11 of extract-agent-round-runner (as amended 2026-11-25): a poll continues a round whose
    //     start already went through execute, so it is forwarded as it is, never recognised
    def "a poll is forwarded to the chain with its turn, returning the delegate's exact result"() {
        given:
        def delegate = Mock(StageExecutor)
        def cell = new CurrentRound()
        def executor = new ResumeVerificationStageExecutor(delegate, cell, pending())
        def turn = new PendingTurn(new PendingKey('job-1'), PendingTurn.Origin.ISSUED)
        def polled = completed()
        def request = request('work', 2)

        when:
        def result = executor.poll(request, turn)

        then:
        1 * delegate.poll(request, turn) >> polled
        0 * delegate.execute(_)
        result.is(polled)

        when: 'the matching start comes after it'
        executor.execute(request)

        then: 'the pending verification was left for it: recovered, no delegation'
        0 * delegate._
        cell.opened() == RECORDED
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

    // Scenario: executor/agent-executor / Pending decision survives factory death
    def "FR15: a pending verification carrying a request re-raises its question with empty telemetry, no agent round"() {
        given:
        def delegate = Mock(StageExecutor)
        def ref = new CurrentRound()
        def executor = new ResumeVerificationStageExecutor(delegate, ref, pending(
                        '{"kind":"decision-request","question":"which db?","options":["pg","sqlite"],"continuationKey":"k-1"}'))

        when:
        def result = executor.execute(request('work', 2))

        then: "the agent never runs, and the cell holds the recorded round"
        0 * delegate.execute(_)
        ref.closed() == new ClosedRound(RECORDED, 'abc123', 'abc123', RoundClose.VERIFIABLE)

        and: "the snapshot's question, mapped through the same mapping a live round's result takes"
        def needed = result.answer() as ExecutionResult.DecisionRequest
        needed.question().forLog() == 'which db?'
        needed.options()*.forLog() == ['pg', 'sqlite']

        and: "the same empty telemetry as the Completed resume: the round's own died with its state commit"
        result.usage() == new ExecutorUsage(Duration.ZERO, [], [:])
        result.trace() == new ToolTrace(new AttemptKey('T-1', 'work', 2), [])
        result.denials().isEmpty()
    }

    // Scenario: executor/agent-executor / Recovery reads a snapshot's answer as the live round does
    def "FR20, FR21, NFR-R5: an answer file the live round would refuse is the live round's failure, never a question"() {
        given:
        def delegate = Mock(StageExecutor)
        def executor = new ResumeVerificationStageExecutor(delegate, new CurrentRound(), pending(text))

        when:
        def result = executor.execute(request('work', 2))

        then: 'no agent round, no decision request raised from the bytes'
        0 * delegate.execute(_)
        result.answer() == null

        and: 'the failure the one mapping gives a refused transform answer (FR20): a quality failure naming the executor and the cause'
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.QUALITY
        failed.reason().forLog() ==~ /agent executor 'claude': wrote (a malformed answer file|an answer file that is no valid answer): .+/
        failed.findings()*.message() == [failed.reason().raw()]
        result.usage() == new ExecutorUsage(Duration.ZERO, [], [:])

        where:
        text << [
            'not json',
            '{"question":"which db?","options":["pg","sqlite"]}'
        ]
    }
}
