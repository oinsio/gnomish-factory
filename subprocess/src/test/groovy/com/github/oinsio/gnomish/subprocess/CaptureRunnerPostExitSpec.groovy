package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16, FR17, NFR-P1, design D15 of fix-terminal-receipt-convergence; FR4, design D2, D4 of
 * make-pipe-reads-non-parking: after the exit the drains are joined with the one post-exit bound —
 * the supervisor's kill grace plus a fixed margin. A drain never parks, so a descendant that holds a
 * pipe and writes nothing lets it end on its own as soon as it sees the exit, the capture complete
 * and not flagged; the bound is reached only by a holder that keeps writing, and then the
 * drain is stopped and the capture flagged as a possible prefix. The silent holder is a real process
 * on purpose (M15): what is measured is that the return does not wait on it. The silent fake holder
 * rides {@link FakeProcess}, whose exit and pipes the spec orders itself (design D16). The cut
 * verdict on a holder that keeps writing is {@code CaptureRunnerCutSpec}'s.
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
    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    def "FR16, M15, FR2: a clean exit with a silent real holder of stdout returns within the bound, complete and not flagged"() {
        given: 'a binary that speaks, exits cleanly, and leaves a child holding its stdout open, writing nothing'
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
        Captured captured = new CaptureRunner(supervisor, ProcessLaunch.grouped(), sweep).run(builder, Duration.ofSeconds(30))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the exit and every byte the child wrote before it, with nothing flagged'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        captured.stdout() == 'spoken\n'
        !captured.truncated()

        and: 'the return did not wait on the holder releasing the pipe — the sweep included'
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

    // Scenario: lifecycle/subprocess-supervision / The drain ends on its own after the exit
    def "FR2, D4: a silent holder of both pipes never flags the capture, whatever the termination: #termination"() {
        given: 'a fake that speaks on both streams while a descendant holds its pipes and writes nothing'
        FakeProcess process = FakeProcess.running()
        process.writeStdout('partial')
        process.writeStderr('fatal: refused\n')
        process.holdOpen()
        if (exitCode != null) {
            process.exit(exitCode)
        }

        and: 'a bound far longer than the return may take'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofSeconds(10))

        when:
        long startedAt = System.nanoTime()
        Captured captured = runnerOver(supervisor, process).run(UNLAUNCHABLE, Duration.ofMillis(200))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the termination the process gave, the capture whole, no notice and no flag'
        captured.termination() == termination
        captured.stdout() == 'partial'
        captured.stderr() == 'fatal: refused\n'
        !captured.truncated()

        and: 'the drains ended on their own, not at the bound'
        elapsed <ProcessSupervisor.POST_EXIT_MARGIN

        cleanup:
        process.release()

        where:
        exitCode | termination
        0 | Termination.EXITED
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
