package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.ChildOutput
import com.github.oinsio.gnomish.subprocess.CutCaptureException
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification
import spock.lang.Timeout

/**
 * FR19 and design D15, D17 of fix-terminal-receipt-convergence; D2, D4, D7 of
 * make-pipe-reads-non-parking: a command check's output tail is read through the one drain, which
 * never parks, and joined with the post-exit bound of the handle the check runs on — never a
 * constant of the runner. A straggler that holds the pipe and stays silent after the check ended
 * costs nothing: the drain ends on its own as soon as it sees the exit and the tail is complete, not
 * flagged (U3 as amended). Only a straggler that keeps writing reaches the bound; the drain is then
 * stopped and the tail names the cut in the one notice's words.
 *
 * <p>The tail is a check's diagnostics, never its value: the verdict is the exit code, so a cut
 * after a clean exit is not refused — the tail still names it, and the classification above
 * discards a passing check's tail (scenario "A failed or killed invocation keeps its partial
 * output" for the failures; D15 as amended 2026-10-17 for the clean exit). Pinned on {@link
 * FakeProcess} with a {@link WritingHolder}, whose writes ride the drain's own queries, so no order
 * is left to the scheduler.
 */
class CommandProcessRunnerCutTailSpec extends Specification {

    private static final Duration HANDLE_BOUND = Duration.ofMillis(100)

    def runner = new CommandProcessRunner('sh', new VirtualClock())

    // Scenario: lifecycle/subprocess-supervision / A failed or killed invocation keeps its partial output
    @Timeout(30)
    def "FR19, D4: a straggler still writing after #ending costs the handle's bound and the tail names the cut"() {
        given: 'a check that wrote a line, then ended while a descendant holds its pipe and keeps writing to it'
        def process = FakeProcess.running()
        process.writeStdout('partial-output\n')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new FakeExecHandle(process, code, wait)
        }

        when:
        long startedAt = System.nanoTime()
        def outcome = runner.run(new VerifyCheck.Command('check'), environment)
        def elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the join let go after the handle\'s bound, well inside the two seconds the runner once held'
        elapsed >= HANDLE_BOUND
        elapsed <Duration.ofMillis(1500)

        and: 'the tail kept the check\'s own line, dropped the cut unterminated dots, and names the cut with the handle\'s bound'
        outcome.outputTail() == CutCaptureException.withNotice('partial-output',
                CutCaptureException.notice(CutCaptureException.CutStreams.STDOUT, HANDLE_BOUND))
        outcome.exitCode() == exitCode
        outcome.termination().getClass() == wait.getClass()

        cleanup:
        process.release()

        where:
        ending | code | wait || exitCode
        'a non-zero exit' | 1 | new ExecHandle.Wait.Exited(Duration.ZERO) || 1
        'a timeout' | 137 | new ExecHandle.Wait.TimedOut() || -1
        'a clean exit' | 0 | new ExecHandle.Wait.Exited(Duration.ZERO) || 0
    }

    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    @Timeout(30)
    def "U3, D4: a silent straggler holding the pipe after #ending leaves the tail complete and names no cut"() {
        given: 'a check that wrote a line, then ended while a descendant holds its pipe and writes nothing'
        def process = FakeProcess.running()
        process.writeStdout('whole-output\n')
        process.holdOpen()
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new FakeExecHandle(process, code, wait)
        }

        when:
        def outcome = runner.run(new VerifyCheck.Command('check'), environment)

        then: 'the drain ended on its own after the exit: the tail is the check\'s whole output, with no notice'
        outcome.outputTail() == 'whole-output'
        outcome.exitCode() == exitCode
        outcome.termination().getClass() == wait.getClass()

        cleanup:
        process.release()

        where:
        ending | code | wait || exitCode
        'a non-zero exit' | 1 | new ExecHandle.Wait.Exited(Duration.ZERO) || 1
        'a timeout' | 137 | new ExecHandle.Wait.TimedOut() || -1
        'a clean exit' | 0 | new ExecHandle.Wait.Exited(Duration.ZERO) || 0
    }

    def "FR19: a tail read to its end carries no cut notice"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout('whole-output\n')
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> new FakeExecHandle(process, 3, new ExecHandle.Wait.Exited(Duration.ZERO))
        }

        when:
        def outcome = runner.run(new VerifyCheck.Command('check'), environment)

        then:
        outcome.outputTail() == 'whole-output'
        outcome.exitCode() == 3
    }

    /**
     * A handle over a {@link FakeProcess} whose wait first lets the drain read what the check wrote
     * — the latch, not a scheduling premise — then ends the fake with {@code code} and reports
     * {@code wait}; its post-exit bound is the spec's short one.
     */
    private static final class FakeExecHandle implements ExecHandle {

        private final FakeProcess process
        private final ChildOutput stdout
        private final int code
        private final ExecHandle.Wait wait

        FakeExecHandle(FakeProcess process, int code, ExecHandle.Wait wait) {
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
        ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
            assert process.awaitStdoutRead(Duration.ofSeconds(10))
            process.exit(code)
            wait
        }

        @Override
        int waitForExit() {
            process.waitFor()
        }

        @Override
        Duration postExitBound() {
            HANDLE_BOUND
        }
    }
}
