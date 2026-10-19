package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import java.time.Duration
import spock.lang.Specification

/**
 * FR18, design D16 of fix-terminal-receipt-convergence: the capture-shaped runner starts its
 * command through the {@link ProcessLaunch} it was handed, never through the builder itself — so a
 * spec drives the drain path on a {@link FakeProcess} whose exit and pipe ends it gates, instead of
 * on a script whose timing became the spec's premise. The builders below name a binary that does
 * not exist: a runner that started them itself would throw instead of returning the fake's capture.
 */
class CaptureRunnerLaunchSpec extends Specification {

    private static final ProcessBuilder UNLAUNCHABLE = new ProcessBuilder('/nonexistent/gnomish-no-such-binary')

    def "FR18: the runner starts the command through the launch it was handed"() {
        given: 'a launch that records the builder and hands back a fake that speaks and exits'
        List<ProcessBuilder> started = []
        List<StdinSource> sources = []
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.writeStderr('noted')
        process.exit(7)
        ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
            started << builder
            sources << stdin
            process
        } as ProcessLaunch

        when:
        Captured captured = new CaptureRunner(new ProcessSupervisor(), launch).run(UNLAUNCHABLE, Duration.ofSeconds(30))

        then: 'the launch was handed the caller\'s builder, once, and no input'
        started.size() == 1
        started[0].is(UNLAUNCHABLE)
        sources == [StdinSource.NONE]

        and: 'the capture is the fake\'s'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 7
        captured.stdout() == 'spoken'
        captured.stderr() == 'noted'
        !captured.truncated()
    }

    def "FR23: the runner hands its input to the launch as the child's stdin source, and writes none itself"() {
        given: 'a launch that records the source, and a fake whose stdin no one may write to'
        List<StdinSource> sources = []
        FakeProcess process = FakeProcess.running()
        process.exit(0)
        ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
            sources << stdin
            process
        } as ProcessLaunch

        when:
        new CaptureRunner(new ProcessSupervisor(), launch).run(UNLAUNCHABLE, Duration.ofSeconds(30), 'input'.bytes)

        then: 'the source carries the whole input'
        sources.size() == 1
        sources[0] instanceof StdinSource.Content
        (sources[0] as StdinSource.Content).bytes() == 'input'.bytes
    }

    // Scenario: lifecycle/subprocess-supervision / A spec controls the exit and the pipes
    def "FR16, FR18: output a holder writes after the exit is captured once it releases the pipe"() {
        given: 'a fake whose pipe outlives its exit, written to after the exit, then released'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('early ')
        process.holdOpen()
        process.exit(0)
        process.writeStdout('late')
        process.release()

        when:
        Captured captured = new CaptureRunner(new ProcessSupervisor(), { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch)
        .run(UNLAUNCHABLE, Duration.ofSeconds(30))

        then: 'the capture is complete and not flagged'
        captured.termination() == Termination.EXITED
        captured.stdout() == 'early late'
        !captured.truncated()
    }

    def "FR16, FR18, FR2: a pipe held past the exit by a silent holder ends on its own, complete and not flagged"() {
        given: 'a fake that exits while a descendant holds its pipes and writes nothing, and a long kill grace'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.holdOpen()
        process.exit(0)
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofSeconds(10))

        when:
        long startedAt = System.nanoTime()
        Captured captured = new CaptureRunner(supervisor, { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch).run(UNLAUNCHABLE, null)

        then: 'the clean exit is returned whole, before the bound, with nothing flagged'
        captured.termination() == Termination.EXITED
        captured.stdout() == 'spoken'
        !captured.truncated()
        Duration.ofNanos(System.nanoTime() - startedAt) <ProcessSupervisor.POST_EXIT_MARGIN

        cleanup:
        process.release()
    }

    def "FR16, FR18, D4: a pipe held past the exit and still written to is cut at the post-exit bound"() {
        given: 'a fake that exits while a descendant holds its pipe and keeps writing, and a short kill grace'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('spoken')
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(0)
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(100))

        when:
        new CaptureRunner(supervisor, { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch).run(UNLAUNCHABLE, null)

        then: 'the clean exit is refused as cut, the prefix kept inside the refusal'
        CutCaptureException refusal = thrown()
        refusal.captured().termination() == Termination.EXITED
        refusal.captured().stdout().startsWith('spoken')
        refusal.captured().truncated()

        cleanup:
        process.release()
    }
}
