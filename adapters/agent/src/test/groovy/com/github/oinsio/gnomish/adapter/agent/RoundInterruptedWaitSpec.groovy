package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
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

    // FR11: the executor's side — a distinct exception, never RoundTimeoutException
    def "FR11: an interrupted wait fails the executor round without blaming the round timeout"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new InterruptedWaitExecHandle()
        }

        when:
        ExecutorRoundExecution.run(
                new FactoryProperties('factory-01', 'claude', Duration.ofSeconds(30), [], null, null, null),
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(),
                new DecisionFileReader(),
                request(),
                'prompt',
                new StandInRound(environment, workspaceDir.resolve('decision.json')))

        then:
        def e = thrown(RoundInterruptedException)
        e.message.contains('interrupted')
        !e.message.contains('roundTimeout')
    }

    // FR11: the judge's side — the never-throw contract holds, and the reason names the interruption
    def "FR11: an interrupted wait yields CannotVerify naming the interruption, not the timeout"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new InterruptedWaitExecHandle()
        }

        when:
        def vote = JudgeRoundExecution.run(
                new FactoryProperties('factory-01', 'claude', Duration.ofSeconds(30), [], null, null, null),
                new VirtualClock(),
                { event -> },
                new AgentRoundResultExtractor(),
                new JudgeVerdictExtractor(),
                new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
                environment,
                'prompt')

        then:
        def verdict = vote.verdict() as Verdict.CannotVerify
        verdict.reason().contains('interrupted')
        !verdict.reason().contains('exceeded')
    }

    private StageExecutor.Request request() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}

/**
 * A handle whose wait was cut short by an interrupt — the tree already killed, nothing known about
 * the work. Its stdout is empty and ends at once: the round must decide on the wait's outcome
 * before it ever consults what the drain read.
 */
class InterruptedWaitExecHandle implements ExecHandle {

    @Override
    InputStream output() {
        new ByteArrayInputStream(new byte[0])
    }

    @Override
    Instant startedAt() {
        Instant.EPOCH
    }

    @Override
    Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
        new Wait.Interrupted()
    }

    @Override
    int waitForExit() {
        0
    }
}
