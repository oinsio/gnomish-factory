package com.github.oinsio.gnomish.adapter.command

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification
import spock.lang.Timeout

/**
 * FR4, FR7, NFR-O1, NFR-S1 (design D2) of add-command-executor: the shared command-run core
 * runs {@code <shell> -c <command>}
 * through the task environment's {@code exec}, the sole process seam, with the caller's env
 * fragment and stderr merged, bounds the wait by the caller's timeout, and reports how the run
 * ended — exited with a code, timed out, interrupted, or never started — with the bounded tail of
 * the merged stream. It classifies nothing: no exit code means anything to it.
 *
 * <p>Pinned on {@link FakeProcess} behind a scripted handle whose wait first lets the drain read
 * what the command wrote, so no order is left to the scheduler.
 */
class CommandRunSpec extends Specification {

    private static final Duration TIMEOUT = Duration.ofMinutes(7)

    def run = new CommandRun('sh', new VirtualClock())

    @Timeout(30)
    def "D2: the command runs as <shell> -c through exec with the env fragment verbatim and stderr merged"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('ok\n')
        def handle = new ScriptedHandle(process, 0, new ExecHandle.Wait.Exited(Duration.ofSeconds(3)))
        def environment = Mock(TaskExecutionEnvironment)

        when:
        run.run(environment, 'make test', [GNOMISH_FINDINGS_FILE: '/scratch/f.json', OTHER: 'x'], TIMEOUT)

        then: 'exactly the fragment the caller gave, no stdin, one chronological stream'
        1 * environment.exec({ ExecCommand c ->
            c.command() == ['sh', '-c', 'make test'] &&
            c.env() == [GNOMISH_FINDINGS_FILE: '/scratch/f.json', OTHER: 'x'] &&
            c.stdin() == null &&
            c.stderr() == StderrPolicy.MERGED
        }) >> handle

        and: 'the wait is bounded by the caller\'s timeout, not a constant of the core'
        handle.timeoutSeen == TIMEOUT
    }

    @Timeout(30)
    def "D2: a command that exits reports its own code, its wall time and its merged tail"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('built\nfailed: 2 tests\n')
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new ScriptedHandle(process, code, new ExecHandle.Wait.Exited(Duration.ofSeconds(3)))
        }

        when:
        def outcome = run.run(environment, 'make test', [:], TIMEOUT)

        then: 'the core reports the code as the command chose it, meaning nothing by it'
        outcome == new CommandRunOutcome.Exited(code, 'built\nfailed: 2 tests', Duration.ofSeconds(3))

        where:
        code << [0, 1, 126, 127]
    }

    @Timeout(30)
    def "D2: a run that #ending reports #kind with the tail captured so far and no exit code"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('partial\n')
        def handle = new ScriptedHandle(process, 137, wait)
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }

        when:
        def outcome = run.run(environment, 'sleep 99', [:], TIMEOUT)

        then:
        outcome.getClass() == kind
        outcome.outputTail() == 'partial'
        !outcome.elapsed().isNegative()
        outcome.elapsed() <Duration.ofMinutes(1)

        and: 'the killed tree\'s code is the signal\'s; the core never asks for it'
        handle.exitCodeAsked == 0

        where:
        ending | wait || kind
        'hit its timeout' | new ExecHandle.Wait.TimedOut() || CommandRunOutcome.TimedOut
        'was interrupted' | new ExecHandle.Wait.Interrupted() || CommandRunOutcome.Interrupted
    }

    // Audit 2026-11-05, recommendation 6: one source of time for every ending's span
    @Timeout(30)
    def "D2: a run that #ending measures its span from the handle's start on the caller's clock, as an exited run does"() {
        given: "the handle started at the epoch; the caller's clock reads ninety seconds later"
        def clock = new VirtualClock()
        clock.advance(Duration.ofSeconds(90))
        def process = FakeProcess.running()
        process.writeStdout('partial\n')
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new ScriptedHandle(process, 137, wait)
        }

        when:
        def outcome = new CommandRun('sh', clock).run(environment, 'sleep 99', [:], TIMEOUT)

        then:
        outcome.elapsed() == Duration.ofSeconds(90)

        where:
        ending | wait
        'hit its timeout' | new ExecHandle.Wait.TimedOut()
        'was interrupted' | new ExecHandle.Wait.Interrupted()
    }

    def "D2: a process the environment cannot start is reported as NotStarted with its cause, never thrown"() {
        given:
        def failure = new ProcessStartException('cannot start sh', new IOException('no such file'))
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> { throw failure }
        }

        when:
        def outcome = run.run(environment, 'make test', [:], TIMEOUT)

        then:
        outcome == new CommandRunOutcome.NotStarted(failure)
    }

    @Timeout(30)
    def "D2: the tail is bounded to the last 200 lines of the merged stream"() {
        given: 'one line over the line bound'
        def process = FakeProcess.running()
        process.writeStdout((1..201).collect { "line-$it\n" }.join())
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new ScriptedHandle(process, 0, new ExecHandle.Wait.Exited(Duration.ZERO))
        }

        when:
        def outcome = run.run(environment, 'noisy', [:], TIMEOUT)

        then: 'the oldest line is evicted; the tail is the end of the stream'
        def lines = outcome.outputTail().readLines()
        lines.size() == 200
        lines.first() == 'line-2'
        lines.last() == 'line-201'
    }

    /**
     * A handle over a {@link FakeProcess} whose wait first lets the drain read what the command
     * wrote — the latch, not a scheduling premise — then ends the fake with {@code code}, records
     * the timeout it was handed, and reports {@code wait}.
     */
    private static final class ScriptedHandle implements ExecHandle {

        private final FakeProcess process
        private final ChildOutput stdout
        private final int code
        private final Wait wait
        volatile Duration timeoutSeen
        volatile int exitCodeAsked

        ScriptedHandle(FakeProcess process, int code, Wait wait) {
            this.process = process
            this.stdout = ChildOutput.stdout(process)
            this.code = code
            this.wait = wait
        }

        @Override
        ChildOutput output() {
            stdout
        }

        @Override
        Instant startedAt() {
            Instant.EPOCH
        }

        @Override
        Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
            timeoutSeen = timeout
            assert process.awaitStdoutRead(Duration.ofSeconds(10))
            process.exit(code)
            wait
        }

        @Override
        int waitForExit() {
            exitCodeAsked++
            process.waitFor()
        }

        @Override
        Duration postExitBound() {
            Duration.ofSeconds(2)
        }
    }
}
