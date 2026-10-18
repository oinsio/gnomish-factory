package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, FR17, NFR-P1, design D15 of fix-terminal-receipt-convergence: after a clean exit the drains
 * are joined with the one post-exit bound — the supervisor's kill grace plus a fixed margin — and
 * when a descendant still holds a pipe the streams are closed and the capture is flagged as a
 * possible prefix. The holder is a real process on purpose (M15): what is measured is that the
 * return does not wait on it. Design D15 as amended 2026-10-17: a cut clean exit with code zero is
 * refused with {@link CutCaptureException}, every other cut termination keeps its partial output —
 * pinned on {@link FakeProcess}, whose exit and pipes the spec orders itself (design D16).
 */
class CaptureRunnerPostExitSpec extends Specification implements FakeBinaries {

    private static final ProcessBuilder UNLAUNCHABLE = new ProcessBuilder('/nonexistent/gnomish-no-such-binary')

    @TempDir
    Path dir

    private static CaptureRunner runnerOver(ProcessSupervisor supervisor, FakeProcess process) {
        new CaptureRunner(supervisor, { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch)
    }

    // Scenario: lifecycle/subprocess-supervision / A clean exit with a held pipe returns within the bound
    def "FR16, FR17, M15: a clean exit with a held stdout returns within the post-exit bound, refused as cut"() {
        given: 'a binary that speaks, exits cleanly, and leaves a child holding its stdout open'
        Path pidFile = dir.resolve('holder.pid')
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('exit-leaky').toString(), pidFile.toString())

        and: 'a supervisor with a short kill grace, so the derived bound stays small'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(300))
        Duration bound = supervisor.postExitBound()

        and: 'the real group sweep, noting whether the holder was alive when it began (M15)'
        Map<String, Boolean> atSweep = [:]
        ProcessGroupSweep sweep = new ProcessGroupSweep(new ProcessProbes(GroupedLaunch.groupSignal(), [], {
            atSweep.holderAlive = holder(pidFile).isAlive()
        }))

        when:
        long startedAt = System.nanoTime()
        new CaptureRunner(supervisor, ProcessLaunch.grouped(), sweep).run(builder, Duration.ofSeconds(30))

        then: 'the cut clean exit is refused, the exit and the prefix inside the refusal'
        CutCaptureException refusal = thrown()
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        refusal.captured().termination() == Termination.EXITED
        refusal.captured().exitCode() == 0
        refusal.captured().stdout().contains('spoken')
        refusal.captured().truncated()

        and: 'the return came on the bound, not on the holder releasing the pipe — the sweep included'
        elapsed <bound + Duration.ofMillis(100)

        and: 'M15: the holder was alive before the sweep and is gone after it'
        atSweep.holderAlive
        ended(pidFile)

        cleanup:
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A cut capture says so
    def "FR17: a clean exit with code zero and a held pipe is refused with the prefix, the stream and the bound"() {
        given: 'a fake that speaks on both streams and exits with zero while a descendant holds its pipes'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('abc12')
        process.writeStderr('noted')
        process.holdOpen()
        process.exit(0)
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(100))

        when:
        runnerOver(supervisor, process).run(UNLAUNCHABLE, null)

        then: 'the refusal carries the capture, the exit and the termination keeping their meaning'
        CutCaptureException refusal = thrown()
        refusal.captured().termination() == Termination.EXITED
        refusal.captured().exitCode() == 0
        refusal.captured().stdout() == 'abc12'
        refusal.captured().stderr() == 'noted'
        refusal.captured().truncated()

        and: 'which streams were cut, and the bound, named in the refusal and its message'
        refusal.streams() == CutCaptureException.CutStreams.BOTH
        refusal.bound() == supervisor.postExitBound()
        refusal.message == 'output was cut: stdout and stderr still open at the post-exit bound of ' +
                supervisor.postExitBound() + ' — a descendant held its pipes after the command exited'

        cleanup:
        process.release()
    }

    def "FR17: the refusal names only the stream that was still held"() {
        given: 'a fake whose stderr reaches end-of-file at the exit while its stdout stays held'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('abc12')
        process.holdOpen()
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
    def "FR17, FR3: a non-zero exit or a timeout with a held pipe keeps its own failure and its partial output"() {
        given: 'a fake that speaks and holds its pipes, then exits with the given code or never exits'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('partial')
        process.writeStderr('fatal: refused\n')
        process.holdOpen()
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
        captured.stdout() == 'partial'
        captured.stderr() == 'fatal: refused\n(output was cut: stdout and stderr still open at the post-exit bound of ' +
                supervisor.postExitBound() + ' — a descendant held its pipes after the command exited)'

        cleanup:
        process.release()

        where:
        exitCode | termination
        1 | Termination.EXITED
        null | Termination.TIMED_OUT
    }

    def "NFR-P1, design D15: the post-exit bound is the kill grace plus a margin of seconds"() {
        expect:
        new ProcessSupervisor(Duration.ofMillis(300)).postExitBound() ==
                Duration.ofMillis(300) + ProcessSupervisor.POST_EXIT_MARGIN
        ProcessSupervisor.POST_EXIT_MARGIN >= Duration.ofSeconds(1)
    }

    // Scenario: lifecycle/subprocess-supervision / A clean exit with no holder pays nothing
    def "NFR-P1: a clean exit with no holder returns at end-of-file, complete and not flagged"() {
        given: 'a binary that writes a few lines to each stream and exits cleanly, holding nothing open'
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('noisy').toString())
        TestChildEnvironment.cleared(builder).putAll([NOISY_LINE: 'spoken', NOISY_LINES: '3'])

        and: 'a kill grace long enough that a return paying the post-exit bound cannot pass for one at end-of-file'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofSeconds(10))
        Duration bound = supervisor.postExitBound()

        when:
        long startedAt = System.nanoTime()
        Captured captured = new CaptureRunner(supervisor, ProcessLaunch.grouped()).run(builder, Duration.ofSeconds(30))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the command exited on its own'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0

        and: 'both drains ended at end-of-file: the capture is whole and not flagged'
        captured.stdout() == 'spoken\n' * 3
        captured.stderr() == 'spoken\n' * 3
        !captured.truncated()

        and: 'the return did not wait the post-exit bound'
        elapsed <bound - ProcessSupervisor.POST_EXIT_MARGIN
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }
}
