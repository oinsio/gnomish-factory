package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4, M1, M2 of fix-round-stdout-drain: rounds whose stream-json output
 * exceeds the OS pipe buffer, driven through the real
 * {@code ProcessBuilder}/pipes path against the fake agent binary. Against the
 * pre-drain code every scenario here failed — the executor and judge read stdout
 * only after {@code waitForExitOrTimeout} returned, so a stream past ~64 KB
 * either blocked the child on a full pipe until the {@code roundTimeout} kill or
 * lost the tail carrying the result event.
 */
class LargeStreamRoundSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    private static final PipelineLaw EXECUTOR_LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])
    private static final PipelineLaw JUDGE_LAW = PipelineLaw.ofContent(['criteria.md': 'The output must be correct.'])

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
        Files.writeString(workspaceDir.resolve('criteria.md'), 'The output must be correct.')
    }

    // M1, FR1: over a megabyte of stream-json, and the result event still lands.
    def "executor round with a megabyte of stream-json completes with its result"() {
        given:
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor('megabyte-round'), clock, EXECUTOR_LAW)

        when:
        def result = executor.execute(requestFor())

        then:
        result instanceof ExecutionResult.Completed
        !result.usage().tokensByModel().isEmpty()
    }

    // M1, FR1: the judge half — a verbose vote is graded, not degraded to CannotVerify.
    def "judge round with a megabyte of stream-json is graded"() {
        given:
        def voter = new CliJudgeVoter(
                FakeAgentSupport.propertiesFor('judge-verdict-pass-megabyte'), clock, JUDGE_LAW)

        when:
        def vote = voter.vote(judgeCheck(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.Pass
    }

    // M2: a synchronous writer past the pipe buffer used to hang until the roundTimeout
    // kill; with the drain it finishes in seconds, far inside a 30-second budget.
    def "synchronous writer past the pipe buffer completes well within roundTimeout"() {
        given:
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor('chatty-sync-writer'), clock, EXECUTOR_LAW)

        when:
        long startedAt = System.nanoTime()
        def result = executor.execute(requestFor(['roundTimeout': '30s']))
        long elapsedMillis = (System.nanoTime() - startedAt).intdiv(1_000_000)

        then:
        result instanceof ExecutionResult.Completed

        and: 'nowhere near the round budget it would have burned waiting on a full pipe'
        elapsedMillis < 15_000
    }

    // FR4, UX1: progress is live — a tool-started event is observed while the process
    // is still running, not as a post-exit burst.
    def "progress events are observed before the process exits"() {
        given: 'a listener that releases a latch on the round\'s tool-started event'
        def toolStarted = new CountDownLatch(1)
        AgentProgressListener listener = { event ->
            if (event instanceof AgentProgressEvent.ToolStarted) {
                toolStarted.countDown()
            }
        }

        and: 'a scenario that emits its whole stream and then sleeps two seconds before exiting'
        def executor = new CliStageExecutor(
                FakeAgentSupport.propertiesFor('plain-round-slow'), clock, listener, EXECUTOR_LAW)

        when: 'the round runs on another thread'
        def finished = new AtomicBoolean()
        def round = Thread.startVirtualThread {
            executor.execute(requestFor())
            finished.set(true)
        }

        then: 'the tool-started event arrives while the round is still in flight'
        toolStarted.await(5, TimeUnit.SECONDS)
        !finished.get()

        cleanup:
        round.join()
    }

    private StageExecutor.Request requestFor(Map<String, Object> settings = [:]) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', settings),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(context(), stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }

    private static VerifyCheck.Judge judgeCheck() {
        new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1)
    }

    private static TaskContext context() {
        new TaskContext('TASK-1', 'title', 'body', [])
    }
}
