package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.time.Duration
import spock.lang.Specification

/**
 * FR21, D15 of add-sandbox-core (the integration pass): an interrupted
 * verification found on resume is consumed by the first matching round — the
 * pending attempt commit is recorded and the agent is never re-run; every
 * other request delegates, and the pending state is consumed exactly once.
 */
class ResumeVerificationStageExecutorSpec extends Specification {

    private static StageExecutor.Request request(String stageName, int attempt) {
        new StageExecutor.Request(
                new TaskContext('T-1', 'title', 'body', List.<Decision> of()),
                new StageDefinition(
                        stageName, 'purpose', [], [],
                        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
                        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO),
                new StubWorkspace(),
                attempt,
                [])
    }

    private static final class StubWorkspace implements Workspace {}

    private static ExecutionResult completed() {
        new ExecutionResult.Completed(
                new ExecutorUsage(Duration.ZERO, [], [:]),
                new ToolTrace(
                        new AttemptKey('T-1', 'work', 1), []), [])
    }

    def "FR21: the matching round skips the agent, records the attempt commit, and completes with empty telemetry"() {
        given:
        def delegate = Mock(StageExecutor)
        def ref = new AttemptCommitRef()
        def executor = new ResumeVerificationStageExecutor(
                delegate, ref, new PendingVerification('abc123', 'work', 2))

        when:
        def result = executor.execute(request('work', 2))

        then: 'no delegation, the pending snapshot becomes the round result'
        0 * delegate.execute(_)
        result instanceof ExecutionResult.Completed
        ref.required() == 'abc123'
        (result as ExecutionResult.Completed).trace().calls().isEmpty()
    }

    def "the pending verification is consumed exactly once — the next matching request delegates"() {
        given:
        def delegate = Mock(StageExecutor)
        def executor = new ResumeVerificationStageExecutor(
                delegate, new AttemptCommitRef(), new PendingVerification('abc123', 'work', 2))
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
        given:
        def delegate = Mock(StageExecutor)
        def ref = new AttemptCommitRef()
        def executor = new ResumeVerificationStageExecutor(
                delegate, ref, new PendingVerification('abc123', 'work', 2))
        def delegateResult = completed()

        when:
        def result = executor.execute(request(stageName, attempt))

        then:
        1 * delegate.execute(_) >> delegateResult
        result.is(delegateResult)

        where:
        stageName | attempt
        'other' | 2
        'work' | 3
    }

    def "a null pending verification is a pure pass-through, returning the delegate's exact result"() {
        given:
        def delegate = Mock(StageExecutor)
        def executor = new ResumeVerificationStageExecutor(delegate, new AttemptCommitRef(), null)
        def delegateResult = completed()

        when:
        def result = executor.execute(request('work', 1))

        then:
        1 * delegate.execute(_) >> delegateResult
        result.is(delegateResult)
    }
}
