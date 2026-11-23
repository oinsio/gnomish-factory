package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.PendingAdoption
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@link PendingTurnRun} (D14 of migrate-verify-to-executor-contract; FR10, FR11 of
 * extract-agent-round-runner, design D11): the engine's turn chooses the contract operation once for
 * a check round and a stage round — no turn is a {@code start}, an issued key a {@code poll}, a
 * recorded key an adoption then a {@code poll} — and the stage round wrapper sends a {@code transform}
 * round's poll through it.
 */
class PendingTurnRunSpec extends Specification {

    static final def KEY = new PendingKey('job-42')
    static final def COMPLETED = new ExecutorResult(Status.COMPLETED, null, null, null, null, Usage.absent(), [])

    def executor = Mock(Executor)
    def adoption = Mock(PendingAdoption)
    def request = ProgramFixtures.request()

    def "no turn starts the request"() {
        when:
        def result = PendingTurnRun.run(executor, adoption, request, null)

        then:
        1 * executor.start(request) >> COMPLETED
        0 * executor.poll(_, _)
        0 * adoption._
        result.is(COMPLETED)
    }

    def "an issued key is polled and never adopted"() {
        when:
        def result = PendingTurnRun.run(executor, adoption, request, new PendingTurn(KEY, PendingTurn.Origin.ISSUED))

        then:
        0 * adoption._
        1 * executor.poll(request, KEY) >> COMPLETED
        0 * executor.start(_)
        result.is(COMPLETED)
    }

    def "a recorded key is adopted, then polled"() {
        when:
        PendingTurnRun.run(executor, adoption, request, new PendingTurn(KEY, PendingTurn.Origin.RECORDED))

        then:
        1 * adoption.adopt(request, KEY)

        then:
        1 * executor.poll(request, KEY) >> COMPLETED
        0 * executor.start(_)
    }

    def "a recorded key of an executor taking no adoption role is polled as it is"() {
        when:
        def result = PendingTurnRun.run(executor, null, request, new PendingTurn(KEY, PendingTurn.Origin.RECORDED))

        then:
        1 * executor.poll(request, KEY) >> COMPLETED
        result.is(COMPLETED)
    }

    // FR10 of extract-agent-round-runner (D11): a stage round carrying the engine's issued turn is a
    //     poll of the round's key, not a second start
    def "the stage round wrapper polls the key a pending round's request names"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            readDenials() >> new DenialRead([], Optional.empty())
        }
        def round = Stub(RoundEnvironmentSource.Round) {
            environment() >> environment
        }
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }
        def factory = new RoundExecutorFactory() {
                    @Override
                    String name() {
                        'remote'
                    }

                    @Override
                    ExecutorRequest request(StageExecutor.Request stageRound) {
                        ProgramFixtures.request()
                    }

                    @Override
                    Executor executor(TaskExecutionEnvironment bound) {
                        executor
                    }
                }
        def start = ProgramFixtures.round(Path.of('.'))
        def poll = new StageExecutor.Request(start.context(), start.stage(), start.workspace(), 0, [],
        new PendingTurn(KEY, PendingTurn.Origin.ISSUED))

        when:
        def result = new ProgramStageExecutor(factory, rounds).execute(poll)

        then:
        1 * executor.poll(_, KEY) >> COMPLETED
        0 * executor.start(_)
        result.status() instanceof ExecutionResult.Completed
    }
}
