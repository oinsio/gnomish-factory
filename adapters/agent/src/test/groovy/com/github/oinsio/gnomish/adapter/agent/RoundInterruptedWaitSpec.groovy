package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR11 of bound-subprocess-commands: the environment's wait now has a third, named outcome —
 * the wait was cut short by an interrupt (a shutdown, a revoked claim), the process tree was killed
 * and nothing is known about the work. Both round sides must report that as its own infrastructure
 * failure and never as the round budget having expired: an operator told a round "exceeded its
 * roundTimeout" during a shutdown goes and raises a number that was never the problem.
 */
class RoundInterruptedWaitSpec extends Specification {

    @TempDir
    Path workspaceDir

    // FR11: the executor's side — a distinct failure, never RoundTimeoutException; FR7 of
    //     define-executor-contract: reported as failed/infrastructure, never thrown, and the
    //     interrupt stays set on the thread so the run above can stop
    def "FR11: an interrupted wait fails the executor round without blaming the round timeout"() {
        given: 'a wait that, like the supervisor, restores the interrupt and took 42 s of the clock'
        def clock = new VirtualClock()
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> new InterruptedWaitExecHandle(clock: clock, restoresInterrupt: true)
        }
        def round = new StandInRound(environment, workspaceDir.resolve('decision.json'))
        def request = StageExecutorRequests.request(workspaceDir)

        when:
        def result = ExecutorRoundExecution.run(
                new AgentRoundEquipment(
                        new FactoryProperties('factory-01', 'claude', null, null),
                        clock,
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                request,
                'prompt',
                round)
        def stillInterrupted = Thread.interrupted()

        then: 'the round reports its failure as the contract status, naming the interruption'
        def failed = InfrastructureResults.of(result)
        InfrastructureResults.endedBy(result, RoundInterruptedException)
        failed.reason().contains('interrupted')
        !failed.reason().contains('roundTimeout')

        and: 'it carries the wall time the round spent, under the round\'s own key'
        result.usage().wallTime() == Duration.ofSeconds(42)
        result.trace().key() == new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt())

        and: 'the cancellation is not swallowed, and the round was discarded'
        stillInterrupted
        round.discarded
    }

    // FR11: the judge's side — the never-throw contract holds, and the reason names the interruption
    def "FR11: an interrupted wait yields CannotVerify naming the interruption, not the timeout"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> new InterruptedWaitExecHandle()
        }

        when:
        def vote = JudgeRoundExecution.run(
                new AgentRoundEquipment(
                        new FactoryProperties('factory-01', 'claude', null, null),
                        new VirtualClock(),
                        { event -> },
                        new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then:
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().forLog().contains('interrupted')
        !verdict.reason().forLog().contains('exceeded')
    }
}

/**
 * A handle whose wait was cut short by an interrupt — the tree already killed, nothing known about
 * the work. Its stdout is empty and ends at once: the round must decide on the wait's outcome
 * before it ever consults what the drain read.
 */
class InterruptedWaitExecHandle implements ExecHandle {

    /** When set, the wait advances it by 42 s, the time the round spent. */
    VirtualClock clock

    /** Whether the wait leaves the interrupt flag set, as {@code ProcessSupervisor} does. */
    boolean restoresInterrupt

    @Override
    ChildOutput output() {
        CannedOutputs.written('')
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    Wait waitForExitOrTimeout(Duration timeout, InstantSource ignored) {
        clock?.advance(Duration.ofSeconds(42))
        if (restoresInterrupt) {
            Thread.currentThread().interrupt()
        }
        new Wait.Interrupted()
    }

    @Override
    int waitForExit() {
        0
    }

    @Override
    Duration postExitBound() {
        Duration.ofSeconds(1)
    }
}
