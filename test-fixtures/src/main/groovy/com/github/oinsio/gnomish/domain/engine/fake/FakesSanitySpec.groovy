package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * Sanity spec for the engine executor, check-runner and external-client
 * fakes: exercises their scripting, recording and throw-mode
 * behavior so the fakes are proven to compile and work for the section 4–7
 * orchestration specs. These are test fakes for the add-stage-engine ports,
 * not production code.
 */
class FakesSanitySpec extends Specification {

    private static final TaskContext CTX = new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])
    private static final FakeWorkspace WS = new FakeWorkspace()

    private static ToolTrace trace() {
        new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    }

    private static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), trace(), [])
    }

    private static StageExecutor.Request request(int attempt) {
        new StageExecutor.Request(CTX, null, WS, attempt, [])
    }

    // Suppressed: IntelliJ's Groovy static checker misinfers the [r0, r1] literal's common
    // type where r0/r1 are different permitted subtypes of the sealed ExecutionResult
    // interface; the constructor accepts List<ExecutionResult> and this resolves correctly
    // at runtime — this is an IDE-only false positive (see BoardModelEligibilitySpec).
    @SuppressWarnings('GroovyAssignabilityCheck')
    def "ScriptedExecutor returns queued results in order and records each request"() {
        given: 'an executor scripted with two results'
        def r0 = completed()
        def r1 = ExecutionResult.decisionNeeded(UntrustedText.agent('Q?'), [], ExecutorUsage.none(), trace(), [])
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
}
