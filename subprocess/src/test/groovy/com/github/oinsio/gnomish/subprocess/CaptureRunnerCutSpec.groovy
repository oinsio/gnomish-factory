package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import java.time.Duration
import spock.lang.Specification

/**
 * FR17, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17; D4 of
 * make-pipe-reads-non-parking: a drain that never parks reaches the post-exit bound only while a
 * holder keeps writing every tick after the exit; it is then stopped and the capture is cut. A cut
 * clean exit with code zero is refused with {@link CutCaptureException}, every other cut termination
 * keeps its partial output with the cut named at the end of stderr. Pinned on {@link FakeProcess}
 * with a {@link WritingHolder}, whose writes ride the drain's own queries (design D6), so no order is
 * left to the scheduler; the writer's dots follow the spoken prefix, which is what is asserted.
 */
class CaptureRunnerCutSpec extends Specification {

    private static final ProcessBuilder UNLAUNCHABLE = new ProcessBuilder('/nonexistent/gnomish-no-such-binary')

    private static CaptureRunner runnerOver(ProcessSupervisor supervisor, FakeProcess process) {
        new CaptureRunner(supervisor, { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch)
    }

    // Scenario: lifecycle/subprocess-supervision / A cut capture says so
    def "FR17, D4: a clean exit with code zero and a holder still writing is refused with the prefix, the stream and the bound"() {
        given: 'a fake that speaks on both streams and exits with zero; a descendant holds its pipes and keeps writing to stdout'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('abc12')
        process.writeStderr('noted')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(0)
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(100))

        when:
        runnerOver(supervisor, process).run(UNLAUNCHABLE, null)

        then: 'the refusal carries the capture, the exit and the termination keeping their meaning'
        CutCaptureException refusal = thrown()
        refusal.captured().termination() == Termination.EXITED
        refusal.captured().exitCode() == 0
        refusal.captured().stdout().startsWith('abc12')
        refusal.captured().stderr() == 'noted'
        refusal.captured().truncated()

        and: 'the stream still open at the bound, and the bound, named in the refusal and its message'
        refusal.streams() == CutCaptureException.CutStreams.STDOUT
        refusal.bound() == supervisor.postExitBound()
        refusal.message == 'output was cut: stdout still open at the post-exit bound of ' +
                supervisor.postExitBound() + ' — a descendant held its pipes after the command exited'

        cleanup:
        process.release()
    }

    def "FR17, D4: the refusal names only the stream still written to, a released stderr ending at the exit"() {
        given: 'a fake whose stderr reaches end-of-file at the exit while its stdout is held and written to'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('abc12')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.releaseStderr()
        process.exit(0)

        when:
        runnerOver(new ProcessSupervisor(Duration.ofMillis(100)), process).run(UNLAUNCHABLE, null)

        then:
        CutCaptureException refusal = thrown()
        refusal.streams() == CutCaptureException.CutStreams.STDOUT
        refusal.message.startsWith('output was cut: stdout still open')

        cleanup:
        process.release()
    }

    // Scenario: lifecycle/subprocess-supervision / A failed or killed invocation keeps its partial output
    def "FR17, FR3: a non-zero exit or a timeout with a holder still writing keeps its own failure and its partial output"() {
        given: 'a fake that speaks, holds its pipes with a descendant writing to stdout, then exits with the code or never'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('partial')
        process.writeStderr('fatal: refused\n')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        if (exitCode != null) {
            process.exit(exitCode)
        }
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(100))

        when:
        Captured captured = runnerOver(supervisor, process).run(UNLAUNCHABLE, Duration.ofMillis(200))

        then: 'no refusal: the failure stands as the termination and exit the process gave'
        captured.termination() == termination
        captured.truncated()

        and: 'the partial output is readable, the cut named once at the end of stderr'
        captured.stdout().startsWith('partial')
        captured.stderr() == 'fatal: refused\n(output was cut: stdout still open at the post-exit bound of ' +
                supervisor.postExitBound() + ' — a descendant held its pipes after the command exited)'

        cleanup:
        process.release()

        where:
        exitCode | termination
        1 | Termination.EXITED
        null | Termination.TIMED_OUT
    }
}
