package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What an executor round leaves behind and what it reports when its stream is poor: the stdout
 * reader of every round ends with the round, whichever way the round ends, and telemetry that
 * cannot be read degrades the round's usage, never the round — while the wall time, which the
 * adapter measures itself, does not depend on the stream at all.
 *
 * <p>FR4, NFR-R1, NFR-R2, D3 of add-agent-executor; FR1, NFR-R1 of fix-round-stdout-drain.
 */
class AgentRoundTeardownSpec extends Specification {

    @TempDir
    Path workspaceDir

    /** A result event whose usage fields are all unreadable; no tool was called. */
    static final String UNREADABLE_TELEMETRY =
    '{"type":"system","subtype":"init","session_id":"s-1","model":"claude-fake-main-1"}\n' +
    '{"type":"result","subtype":"success","session_id":"s-1","result":"done",' +
    '"usage":{"input_tokens":"many"},"modelUsage":{"claude-fake-main-1":"not-an-object"}}\n'

    /** The command the round launched last: where its stderr went. */
    ExecCommand launched

    private ExecutionResult runRound(ExecHandle handle) {
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                launched = command
                handle
            }
        }
        ExecutorRoundExecution.run(
                new AgentRoundEquipment(new FactoryProperties('factory-01', 'claude', null, null),
                new VirtualClock(), { event -> }, new AgentRoundResultExtractor(), ChildEnvAllowlist.none(), RoundScratchDirectory.HOST_TEMP),
                new DecisionFileReader(),
                StageExecutorRequests.request(workspaceDir),
                'prompt',
                new StandInRound(environment, workspaceDir.resolve('decision.json')))
    }

    // FR4, NFR-R2, D3: telemetry is best-effort — the round completes, with no usage and no trace
    // F86: scenario text aligned to what this test asserts — no tool or token usage, the wall time still the adapter's
    // Scenario: executor/agent-executor / Telemetry failure does not fail the round
    def "FR4: a stream whose usage fields cannot be parsed completes the round with no tool or token usage, its wall time, and an empty trace"() {
        when:
        def result = runRound(new ScriptedHandle(stream(UNREADABLE_TELEMETRY), new ExecHandle.Wait.Exited(Duration.ofSeconds(7))))

        then:
        result instanceof ExecutionResult.Completed
        result.usage().tokensByModel().isEmpty()
        result.usage().tools().isEmpty()
        result.usage().wallTime() == Duration.ofSeconds(7)
        result.trace().calls().isEmpty()
    }

    // D3: the wall time is the adapter's own measurement of the process, not a stream field
    // Scenario: executor/agent-executor / Wall time does not depend on the stream
    def "D3: a round with no usable telemetry still reports the process's wall time"() {
        when:
        def result = runRound(new ScriptedHandle(stream(UNREADABLE_TELEMETRY), new ExecHandle.Wait.Exited(Duration.ofSeconds(7))))

        then:
        result.usage().wallTime() == Duration.ofSeconds(7)
    }

    // NFR-R1 of fix-round-stdout-drain: try-with-resources around the drain is what guarantees it;
    //     FR20 of fix-terminal-receipt-convergence: the round scratch directory leaves the same way
    // Scenario: executor/agent-executor / No reader outlives the round
    // Scenario: executor/agent-executor / The stderr file leaves with the round
    def "NFR-R1: after a round that #ending, its drain thread has ended, no read is pending and no file of it remains"() {
        given: 'with content, a child that wrote it and exited; without, a live child that writes nothing'
        def child = FakeProcess.running()
        if (content != null) {
            child.writeStdout(content)
            child.exit(0)
        }
        def reader = new AtomicReference<Thread>()
        def reading = new CountDownLatch(1)
        child.stdoutPipe().onQuery {
            reader.set(Thread.currentThread())
            reading.countDown()
        }
        def handle = new ScriptedHandle(ChildOutput.stdout(child), wait, Duration.ofMillis(200),
                { reading.await(5, TimeUnit.SECONDS) })

        when:
        try {
            runRound(handle)
        } catch (ExecutorFailure ignored) {
            // the timeout and the adapter failure end the round by an exception; its teardown is the subject
        }

        then: 'the thread that read the stdout has ended'
        reader.get().join(Duration.ofSeconds(5))
        !reader.get().alive

        and: "no read of the stream is pending: a live child's empty pipe was never read at all"
        content != null || child.stdoutPipe().reads() == 0

        and: 'the round stopped its stdout itself and closed no pipe: the view answers end-of-file at once'
        handle.output().stream().read(new byte[8]) == -1

        and: 'the round scratch directory, with the stderr file in it, left the host with the round'
        !Files.exists((launched.stderr() as StderrPolicy.ToFile).file().parent)

        cleanup:
        child.exit(0)

        where:
        ending | content | wait
        'exited normally' | UNREADABLE_TELEMETRY | new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
        'was killed on its timeout' | null | new ExecHandle.Wait.TimedOut()
        'failed in the adapter' | null | new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
    }

    private static ChildOutput stream(String text) {
        CannedOutputs.written(text)
    }

    /**
     * A process handle whose stdout, wait outcome and post-exit bound the feature scripts; its wait
     * first runs {@code beforeWait}, so a feature can make the drain's first query precede the
     * round's wait, as it does for a live process, instead of trusting the scheduler.
     */
    static final class ScriptedHandle implements ExecHandle {
        final ChildOutput stdout
        final ExecHandle.Wait wait
        final Duration postExitBound
        final Closure<?> beforeWait

        ScriptedHandle(ChildOutput stdout, ExecHandle.Wait wait, Duration postExitBound = Duration.ofSeconds(5),
        Closure<?> beforeWait = {}) {
            this.stdout = stdout
            this.wait = wait
            this.postExitBound = postExitBound
            this.beforeWait = beforeWait
        }

        ChildOutput output() {
            stdout
        }

        Instant startedAt() {
            Instant.EPOCH
        }

        ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
            beforeWait()
            wait
        }

        int waitForExit() {
            0
        }

        Duration postExitBound() {
            postExitBound
        }
    }
}
