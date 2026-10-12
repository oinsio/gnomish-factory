package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
 * lost the tail carrying the result event. The prompt side of the same pipes is here too: a
 * prompt past every argument limit, which travels on stdin.
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
    // Scenario: executor/agent-executor / Megabyte stream keeps its result event
    def "executor round with a megabyte of stream-json completes with its result"() {
        given:
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor('megabyte-round'), clock, EXECUTOR_LAW)

        when:
        def result = executor.execute(FakeAgentSupport.requestFor(workspaceDir))

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
        def vote = voter.vote(judgeCheck(), FakeAgentSupport.defaultTaskContext(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.Pass
    }

    // M2: a synchronous writer past the pipe buffer used to hang until the roundTimeout
    // kill; with the drain it finishes in seconds, far inside a 30-second budget.
    // Scenario: executor/agent-executor / Chatty synchronous writer does not hang
    def "synchronous writer past the pipe buffer completes well within roundTimeout"() {
        given:
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor('chatty-sync-writer'), clock, EXECUTOR_LAW)

        when:
        long startedAt = System.nanoTime()
        def result = executor.execute(FakeAgentSupport.requestFor(workspaceDir, ['roundTimeout': '30s']))
        long elapsedMillis = (System.nanoTime() - startedAt).intdiv(1_000_000)

        then:
        result instanceof ExecutionResult.Completed

        and: 'nowhere near the round budget it would have burned waiting on a full pipe'
        elapsedMillis < 15_000
    }

    // FR4, UX1: progress is live — a tool-started event is observed while the process
    // is still running, not as a post-exit burst.
    // Scenario: executor/agent-executor / Progress is live, not post-mortem
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
            executor.execute(FakeAgentSupport.requestFor(workspaceDir))
            finished.set(true)
        }

        then: 'the tool-started event arrives while the round is still in flight'
        toolStarted.await(5, TimeUnit.SECONDS)
        !finished.get()

        cleanup:
        round.join()
    }

    // FR24, D18 of add-sandbox-core: the prompt travels on stdin, so a prompt past the platform's
    //     single-argument limit (128 KiB on Linux, ARG_MAX 1 MiB in total on macOS) still starts
    // Scenario: executor/agent-executor / Late attempt with a large feedback history starts
    def "FR24: a late attempt whose prompt is larger than any argument limit starts and receives it whole"() {
        given: 'a fourth attempt carrying 2.5 MB of rendered findings from the attempts before it'
        def capture = workspaceDir.resolve('plain-round.log')
        def properties = new FactoryProperties('factory-01', FakeAgentSupport.binaryCapturingStdin('plain-round', capture), null, null)
        def first = FakeAgentSupport.requestFor(workspaceDir)
        def history = (1..2500).collect {
            new CheckResult(new CheckRef(0, UntrustedText.manifest('command:make check')),
            new Verdict.Fail([
                new Finding("finding ${it}: " + ('x' * 1000), null, null)
            ]), Duration.ZERO)
        }
        def request = new StageExecutor.Request(first.context(), first.stage(), first.workspace(), 3, history)
        def prompt = new ExecutorPromptBuilder(EXECUTOR_LAW).build(request)
        assert prompt.length() > 2_500_000

        when:
        def result = new CliStageExecutor(properties, clock, EXECUTOR_LAW).execute(request)

        then: 'the process started and read the whole prompt, nothing cut'
        result instanceof ExecutionResult.Completed
        Files.readString(capture) == prompt + '\n---\n'
    }

    private static VerifyCheck.Judge judgeCheck() {
        new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1)
    }
}
