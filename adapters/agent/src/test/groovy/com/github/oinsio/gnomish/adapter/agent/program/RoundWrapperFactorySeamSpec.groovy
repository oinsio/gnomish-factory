package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
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
 * mapping — so a built-in entry runs its rounds through this wrapper, not a second one. A factory that
 * says nothing more keeps the defaults: its request is not changed by the open round, its executor is
 * bound to the round's environment, and the wrapper's refusals name it a program executor.
 */
class RoundWrapperFactorySeamSpec extends Specification {

    static final Path WORKSPACE = Path.of('.')

    def environment = Stub(TaskExecutionEnvironment) {
        readDenials() >> new DenialRead([], Optional.empty())
    }

    def round = Stub(RoundEnvironmentSource.Round) {
        environment() >> environment
    }

    def rounds = Stub(RoundEnvironmentSource) {
        openRound(_) >> round
    }

    /** A factory of the abstract methods alone, so the wrapper runs the interface's defaults. */
    private static RoundExecutorFactory defaults(TaskExecutionEnvironment bound, Executor run) {
        new RoundExecutorFactory() {
                    @Override
                    String name() {
                        'command'
                    }

                    @Override
                    ExecutorRequest request(StageExecutor.Request round) {
                        ProgramFixtures.request()
                    }

                    // Only the open round's environment gets the executor: any other fails the round.
                    @Override
                    Executor executor(TaskExecutionEnvironment environment) {
                        assert environment.is(bound)
                        run
                    }
                }
    }

    private RoundExecutorFactory factory(ExecutorResult result) {
        // The request the open round sends is the one built before it opened, unchanged by default.
        def run = Stub(Executor) {
            start(ProgramFixtures.request()) >> result
        }
        defaults(environment, run)
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
        def factory = defaults(failing, Stub(Executor))

        when:
        def result = new ProgramStageExecutor(factory, source).execute(ProgramFixtures.round(WORKSPACE))

        then:
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        // FR15 of extract-agent-round-runner (D13): a dead guard is named by the one probe owner
        failed.reason().raw().contains(
                "program executor 'command': the egress guard of its environment was not running before the request: guard down")
    }
}
