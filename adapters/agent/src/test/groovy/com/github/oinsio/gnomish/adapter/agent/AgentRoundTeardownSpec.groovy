package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.ExecutorFailure
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
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

    private ExecutionResult runRound(ExecHandle handle, Duration grace = Duration.ofSeconds(5)) {
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> handle
        }
        ExecutorRoundExecution.run(
                new AgentRoundEquipment(new FactoryProperties('factory-01', 'claude', grace, null, null, null),
                new VirtualClock(), { event -> }, new AgentRoundResultExtractor(), ChildEnvAllowlist.none()),
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

    // NFR-R1 of fix-round-stdout-drain: try-with-resources around the drain is what guarantees it
    // Scenario: executor/agent-executor / No reader outlives the round
    def "NFR-R1: after a round that #ending, its stdout is closed and its drain thread has ended"() {
        given:
        def stdout = new TrackedStream(content)
        def handle = new ScriptedHandle(stdout, wait)

        when:
        try {
            runRound(handle, Duration.ofMillis(200))
        } catch (ExecutorFailure ignored) {
            // the timeout and the adapter failure end the round by an exception; its teardown is the subject
        }

        then: 'the stream was closed by the round itself'
        stdout.closed.await(0, TimeUnit.SECONDS)

        and: 'the thread that read it has ended'
        def reader = stdout.awaitReader()
        reader.join(Duration.ofSeconds(5))
        !reader.isAlive()

        where:
        ending | content | wait
        'exited normally' | UNREADABLE_TELEMETRY | new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
        'was killed on its timeout' | null | new ExecHandle.Wait.TimedOut()
        'failed in the adapter' | null | new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
    }

    private static InputStream stream(String text) {
        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
    }

    /** A process handle whose stdout and wait outcome the feature scripts. */
    static final class ScriptedHandle implements ExecHandle {
        final InputStream stdout
        final ExecHandle.Wait wait

        ScriptedHandle(InputStream stdout, ExecHandle.Wait wait) {
            this.stdout = stdout
            this.wait = wait
        }

        InputStream output() {
            stdout
        }

        Instant startedAt() {
            Instant.EPOCH
        }

        ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
            wait
        }

        int waitForExit() {
            0
        }
    }

    /**
     * A stdout that records the thread reading it and its own close. With content it ends after the
     * content; without, it blocks like a live pipe until it is closed — so only the round's teardown
     * can release its reader (the adapter failure: the drain outlives the tail-drain grace).
     */
    static final class TrackedStream extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1)
        final AtomicReference<Thread> reader = new AtomicReference<>()
        private final InputStream content

        TrackedStream(String text) {
            this.content = text == null ? null : stream(text)
        }

        @Override
        int read() {
            reader.compareAndSet(null, Thread.currentThread())
            if (content != null) {
                return content.read()
            }
            closed.await(10, TimeUnit.SECONDS)
            -1
        }

        @Override
        void close() {
            closed.countDown()
        }

        Thread awaitReader() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reader.get() == null && System.nanoTime() <deadline) {
                Thread.onSpinWait()
            }
            reader.get()
        }
    }
}
