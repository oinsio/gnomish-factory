package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.agent.HostRoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.NoOpenRoundException
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.app.port.check.PendingAdoption
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
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
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10, FR11 of extract-agent-round-runner (design D11 as amended 2026-11-25): {@link
 * ProgramStageExecutor} runs a pending {@code transform} round's {@code start} and polls as one
 * round. A {@code start} that answers {@code pending} goes pending and is not closed; a poll takes
 * the open round from the source, never opens one, writes nothing on a {@code pending} answer and
 * closes on a terminal one, the round's denials read once at that close; a recorded key is handed to
 * the bound executor's adoption role; a poll with no open round is refused.
 */
class ProgramStageExecutorPollSpec extends Specification {

    static final PendingKey KEY = new PendingKey('job-42')
    static final ExecutorResult PENDING = new ExecutorResult(Status.PENDING, null, KEY, null, null, Usage.absent(), [])
    static final ExecutorResult COMPLETED = new ExecutorResult(Status.COMPLETED, null, null, null, null, Usage.absent(), [])
    static final Denial EGRESS = Denial.unidentified(new Finding('egress denied: evil.example', null, null))

    @TempDir
    Path workspace

    /** The bound executor, which also takes the adoption role a program executor takes. */
    interface AdoptingExecutor extends Executor, PendingAdoption {}

    def executor = Mock(AdoptingExecutor)

    def environment = Stub(TaskExecutionEnvironment) {
        readDenials() >> new DenialRead([EGRESS], Optional.empty())
    }

    def round = Mock(RoundEnvironmentSource.Round) {
        environment() >> environment
    }

    private ProgramStageExecutor stageExecutor(RoundEnvironmentSource rounds, Executor bound = executor) {
        new ProgramStageExecutor(new RoundExecutorFactory() {
                    @Override
                    String name() {
                        'remote'
                    }

                    @Override
                    ExecutorRequest request(StageExecutor.Request stageRound) {
                        ProgramFixtures.request()
                    }

                    @Override
                    Executor executor(TaskExecutionEnvironment boundTo) {
                        bound
                    }
                }, rounds)
    }

    private StageExecutor.Request request() {
        ProgramFixtures.round(workspace)
    }

    // FR10 (D11 as amended): going pending is not a close — the round stays open, no denials read
    def "a start that answers pending goes pending and is not closed"() {
        given:
        def rounds = Mock(RoundEnvironmentSource)

        when:
        def result = stageExecutor(rounds).execute(request())

        then:
        1 * rounds.openRound(_) >> round
        1 * executor.start(_) >> PENDING
        1 * round.goPending()
        0 * round.closeRound(_)
        0 * round.discard()
        result.status() instanceof ExecutionResult.Pending
        result.denials() == []
    }

    // FR10 (D11 as amended): a poll continues the open round and, answering pending, writes nothing
    def "a poll answering pending opens, goes pending in, and closes nothing"() {
        given:
        def rounds = Mock(RoundEnvironmentSource)

        when:
        def result = stageExecutor(rounds).poll(request(), new PendingTurn(KEY, PendingTurn.Origin.ISSUED))

        then:
        1 * rounds.continueRound(_) >> round
        0 * rounds.openRound(_)
        1 * executor.poll(_, KEY) >> PENDING
        0 * executor.adopt(_, _)
        0 * round.goPending()
        0 * round.closeRound(_)
        0 * round.discard()
        result.status() instanceof ExecutionResult.Pending
        result.denials() == []
    }

    // FR10 (D11 as amended): the terminal poll closes the round, and its denials join that result
    def "a terminal poll closes the open round and reads its denials once"() {
        given:
        def rounds = Mock(RoundEnvironmentSource)

        when:
        def result = stageExecutor(rounds).poll(request(), new PendingTurn(KEY, PendingTurn.Origin.ISSUED))

        then:
        1 * rounds.continueRound(_) >> round
        0 * rounds.openRound(_)
        1 * executor.poll(_, KEY) >> COMPLETED
        1 * round.closeRound(TerminalClose.VERIFIABLE)
        0 * round.discard()
        result.status() instanceof ExecutionResult.Completed
        result.denials() == [EGRESS]
    }

    // FR11 (D11, "Resume"): a recorded key reaches the bound executor's adoption role before its poll
    def "a recorded key is handed to the bound executor's adoption role, then polled in the open round"() {
        given:
        def rounds = Stub(RoundEnvironmentSource) {
            continueRound(_) >> round
        }

        when:
        stageExecutor(rounds).poll(request(), new PendingTurn(KEY, PendingTurn.Origin.RECORDED))

        then:
        1 * executor.adopt(_, KEY)

        then:
        1 * executor.poll(_, KEY) >> COMPLETED
    }

    // FR11: an executor that takes no adoption role is polled as it is
    def "a recorded key of an executor taking no adoption role is polled as it is"() {
        given:
        def plain = Mock(Executor)
        def rounds = Stub(RoundEnvironmentSource) {
            continueRound(_) >> round
        }

        when:
        def result = stageExecutor(rounds, plain).poll(request(), new PendingTurn(KEY, PendingTurn.Origin.RECORDED))

        then:
        1 * plain.poll(_, KEY) >> COMPLETED
        result.status() instanceof ExecutionResult.Completed
    }

    // FR10 (D11, D14 as amended): a poll never opens a round, so with none open it is the poll's
    //     failed/infrastructure naming the missing round
    // Scenario: pipeline/stage-engine / A poll with no open round is refused
    def "a poll with no open round is failed/infrastructure naming the missing round, and no round is opened"() {
        given: 'the host round source, which holds no open round and has no cell to restore one from'
        def rounds = new HostRoundEnvironmentSource(new VirtualClock(), TestChildEnvironment.hostAllowlist())

        when:
        def result = stageExecutor(rounds).poll(request(), new PendingTurn(KEY, PendingTurn.Origin.ISSUED))

        then:
        0 * executor._
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().contains("program executor 'remote'")
        failed.reason().contains("no round of stage 'build' attempt 0 is open to poll in")
        result.denials() == []

        when: 'no round was opened for it: the source still holds none'
        rounds.continueRound(request())

        then:
        thrown(NoOpenRoundException)
    }
}
