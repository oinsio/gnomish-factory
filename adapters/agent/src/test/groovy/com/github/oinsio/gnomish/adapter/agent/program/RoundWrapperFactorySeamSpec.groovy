package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * The shared round wrapper over a factory that is not a program's (design D2 of add-command-executor):
 * {@link ProgramStageExecutor} asks the factory for the request before the round opens and for the
 * executor in the open round's environment, and maps the executor's contract result through the one
 * mapping — so a built-in entry runs its rounds through this wrapper, not a second one.
 */
class RoundWrapperFactorySeamSpec extends Specification {

    static final Path WORKSPACE = Path.of('.')

    def environment = Stub(TaskExecutionEnvironment) {
        readDenials() >> new DenialRead([], Optional.empty())
    }

    def round = Stub(RoundEnvironmentSource.Round) {
        environment() >> environment
        readDecision() >> Optional.empty()
    }

    def rounds = Stub(RoundEnvironmentSource) {
        openRound(_) >> round
    }

    private RoundExecutorFactory factory(ExecutorResult result) {
        def run = Stub(Executor) {
            start(ProgramFixtures.request()) >> result
        }
        // Only the open round's environment gets the executor: any other answers a default stub,
        // whose null result fails the round as infrastructure.
        Stub(RoundExecutorFactory) {
            name() >> 'command'
            request(_) >> ProgramFixtures.request()
            executor(environment) >> run
        }
    }

    // FR8 of add-command-executor (D2): the factory's executor runs in the round's environment and its result is mapped
    def "FR8: the wrapper runs the factory's executor in the open round and maps its result"() {
        given:
        def contract = new ExecutorResult(Status.FAILED, FailureClass.QUALITY, null,
                UntrustedText.subprocess('exit 1'), null, Usage.absent(), [])

        when:
        def result = new ProgramStageExecutor(factory(contract), rounds).execute(ProgramFixtures.round(WORKSPACE))

        then:
        result.status() instanceof ExecutionResult.Failed
        (result.status() as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.QUALITY
    }

    // FR8 of add-command-executor (D2): a failure of the round's mechanics is named by the factory's name
    def "FR8: a round whose mechanics fail is an infrastructure failure naming the factory's executor"() {
        given:
        def failing = Stub(TaskExecutionEnvironment) {
            probeGuard() >> { throw new IllegalStateException('guard down') }
            readDenials() >> new DenialRead([], Optional.empty())
        }
        def broken = Stub(RoundEnvironmentSource.Round) {
            environment() >> failing
        }
        def source = Stub(RoundEnvironmentSource) {
            openRound(_) >> broken
        }
        def factory = Stub(RoundExecutorFactory) {
            name() >> 'command'
            request(_) >> ProgramFixtures.request()
        }

        when:
        def result = new ProgramStageExecutor(factory, source).execute(ProgramFixtures.round(WORKSPACE))

        then:
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().raw().contains("'command': the round failed before its result was read")
    }
}
