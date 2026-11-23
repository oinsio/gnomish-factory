package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * How an agent round reads its CLI's stdout from the launch to the post-exit bound, on {@link
 * AgentRoundRunner} with {@link FakeDialect} (task 5.1 of extract-agent-round-runner, which moved these
 * scenarios off the deleted old round path; FR1, FR3, FR5, FR6, NFR-R1 of fix-round-stdout-drain): the
 * tail waiting in the pipe at the exit is read, a reader that outlives the bound or an interrupted wait
 * is an infrastructure failure named for what it was, a stream of any size keeps its result, and a
 * broken round names what it read. Each CLI is a {@link FakeProcess} whose exit and pipes the feature
 * gates itself, so no feature rests on a thread order.
 */
@Timeout(60)
class AgentRoundDrainSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    def cleanup() {
        Thread.interrupted()
    }

    /**
     * One start whose CLI the environment reports exited, while its stdout is that of {@code live}, a
     * child that never ends — the shape of a descendant still holding the pipe and writing to it. The
     * round waits on its stream, so it is the startup-read bound (350 ms of virtual time) that hands the
     * round to the exit it then finds; {@code onExitRead} runs on the round's thread as the exit code is read,
     * between the wait for the exit and the wait for the reader.
     */
    private def startHeld(FakeProcess live, Closure onExitRead = {}) {
        def handle = new ExecHandle() {
                    ChildOutput output() {
                        ChildOutput.stdout(live)
                    }

                    Instant startedAt() {
                        Instant.EPOCH
                    }

                    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
                        new ExecHandle.Wait.Exited(Duration.ofSeconds(1))
                    }

                    int waitForExit() {
                        onExitRead()
                        0
                    }

                    Duration postExitBound() {
                        Duration.ofMillis(300)
                    }
                }
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> handle
        }
        def bounds = new ProgramBounds(Duration.ofMillis(350), Duration.ofHours(1), Duration.ofHours(2))
        def equipment = new ProgramRoundEquipment(bounds, fixture.time, ChildEnvAllowlist.none().programGrant(),
                stderrParent, LastRegisteredCommit.NONE)
        new AgentRoundRunner<>('fake', fixture.dialect, environment, equipment, { event -> } as AgentProgressListener,
        StdoutCaps.DEFAULTS).start(RunnerFixture.request(Role.TRANSFORM))
    }

    // Scenario: executor/agent-executor / A drain unfinished after the post-exit bound is infrastructure
    // Scenario: executor/agent-executor / The round's close leaves no drain thread
    // Scenario: executor/agent-executor / No reader outlives the round
    def "FR3, NFR-R1: a stdout that has not ended at the post-exit bound fails the round, and its reader ends"() {
        given: 'the CLI exited, but its stdout is still held by a live descendant, so the stream never ends'
        def live = FakeProcess.running()
        def reader = new AtomicReference<Thread>()
        live.stdoutPipe().onQuery {
            reader.compareAndSet(null, Thread.currentThread())
        }

        when:
        def result = startHeld(live)

        then: 'never a round completed on a stream that did not end'
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': its stdout did not end within the post-exit bound of PT0.3S"

        and: 'the reader thread ended though the holder is alive and no pipe was closed'
        reader.get().join(Duration.ofSeconds(5))
        !reader.get().alive
        live.alive

        cleanup:
        live.exit(0)
    }

    // Scenario: executor/agent-executor / An interrupted wait is not blamed on the bound
    def "FR6: a round thread interrupted while it waits for its reader is an interruption, not an expired post-exit bound"() {
        given: 'the interrupt lands between the wait for the exit and the wait for the reader'
        def live = FakeProcess.running()

        when:
        def result = startHeld(live, { Thread.currentThread().interrupt() })

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the round was interrupted while its stdout was still being read"
        Thread.interrupted()

        cleanup:
        live.exit(0)
    }

    // Scenario: executor/agent-executor / Timeout kill still classifies as infrastructure
    def "NFR-R1: a CLI killed mid-stream by its overall bound is failed/infrastructure, its reader ended without error"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('hello m s\nuse Read\n')
        fixture.bounds = new ProgramBounds(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(350))

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), process)

        then:
        !process.alive
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the overall bound of PT0.35S expired and its process tree was killed"
        fixture.dialect.parsed == ['hello m s', 'use Read']
    }

    // Scenario: executor/agent-executor / Megabyte stream keeps its result event
    def "FR1: over a megabyte of noise before the result event, the round completes with its result and usage"() {
        given:
        def noise = ('noise ' + 'x' * 120 + '\n') * 9_000

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, noise + 'hello m s\ndone all good\n'))

        then:
        noise.length() > 1_048_576
        result.status() == Status.COMPLETED
        fixture.dialect.judged.stamped()*.event()*.kind == ['hello', 'done']
    }

    // Scenario: executor/agent-executor / Diagnostics carry read volume
    def "FR5: a round with no result event names the bytes and events it read"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m s\nuse Read\n'))

        then: '10 + 9 bytes, two events, no boundary in sight'
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().endsWith('no result event — read 19 bytes, 2 event(s)')
    }

    // Scenario: executor/agent-executor / Truncation is hinted at the buffer boundary
    def "FR5: a result-less stream of #bytes bytes #hinted the probable truncation"() {
        given: 'lines of 99 bytes each, line break included, topped up with one shorter line'
        int lines = bytes.intdiv(100)
        def stdout = ('n' * 99 + '\n') * lines + 'n' * (bytes - lines * 100 - 1) + '\n'
        assert stdout.length() == bytes

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, stdout))

        then:
        result.reason().raw().contains("read ${bytes} bytes, 0 event(s)")
        result.reason().raw().contains('pipe-buffer boundary, so the stream was probably truncated') == hint

        where:
        bytes || hint
        65_528 || true
        131_072 || true
        57_344 || true
        57_343 || false
        30_050 || false
        hinted = hint ? 'names' : 'does not name'
    }
}
