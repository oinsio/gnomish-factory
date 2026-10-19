package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D2, FR2, NFR-P1 of bound-subprocess-commands: both streams are drained concurrently with
 * the running process. A pipe nobody
 * reads deadlocks the child once the OS buffer fills — and a caller that reads one stream to the
 * end before waiting is the reproduced hang itself. On the kill path the drain join is bounded:
 * a process that escaped the kill snapshot can hold the inherited pipe open, and "drains joined"
 * must never be a precondition for returning a result.
 */
class CaptureRunnerDrainSpec extends Specification implements FakeBinaries {

    private static final int LINES = 1500
    private static final String LINE = 'x' * 100

    @TempDir
    Path dir

    // Scenario: git/git-task-persistence / A full output pipe does not hide the deadline
    // Scenario: lifecycle/subprocess-supervision / Full pipes never deadlock the wait
    def "FR2, design D2: more than a pipe buffer on both streams completes with both captured in full"() {
        given: 'a binary writing ~150 KiB to each stream — well past any OS pipe buffer'
        Path binary = fakeBinary('noisy')
        ProcessBuilder builder = new ProcessBuilder(binary.toString())
        TestChildEnvironment.cleared(builder).putAll([NOISY_LINE: LINE, NOISY_LINES: String.valueOf(LINES)])

        when:
        Captured captured = new CaptureRunner().run(builder, Duration.ofSeconds(60))

        then: 'the command completed normally rather than deadlocking on a full pipe'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0

        and: 'both streams are captured whole, and kept apart'
        captured.stdout().length() == LINES * (LINE.length() + 1)
        captured.stderr().length() == LINES * (LINE.length() + 1)
    }

    // Scenario: git/git-task-persistence / A full output pipe does not hide the deadline
    // Scenario: lifecycle/subprocess-supervision / An inherited pipe cannot block the return
    // Scenario: lifecycle/subprocess-supervision / A stalled child cannot outlive its deadline
    def "design D2: on the kill path a straggler holding the pipe does not block the return"() {
        given: 'a binary that leaks a holder of its stdout out of its own process tree before stalling'
        Path pidFile = dir.resolve('holder.pid')
        Path binary = fakeBinary('leaky')

        and: 'a runner whose kill-path drain join is a fraction of what the holder will live, its sweep'
        and: 'noting whether the holder was still alive when the drains had let go'
        Map<String, Boolean> atSweep = [:]
        ProcessGroupSweep sweep = new ProcessGroupSweep(new ProcessProbes(GroupedLaunch.groupSignal(), [], {
            atSweep.holderAlive = holder(pidFile).isAlive()
        }))
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), ProcessLaunch.grouped(), sweep)

        when:
        long startedAt = System.nanoTime()
        Captured captured = runner.run(new ProcessBuilder(binary.toString(), pidFile.toString()), Duration.ofSeconds(2))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the deadline is reported, with whatever the drains had read by then'
        captured.termination() == Termination.TIMED_OUT
        captured.stdout().contains('started')

        and: 'the return happened on the bound, not on the pipe closing'
        elapsed <Duration.ofSeconds(6)

        and: 'proof the pipe was still held open when the drains let go; the group sweep ended the holder after'
        atSweep.holderAlive
        ended(pidFile)

        cleanup:
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
            killQuietly(it)
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A spec controls the exit and the pipes
    def "design D2, NFR-R3, FR16: a normal exit keeps output a holder writes after it while the drain still reads"() {
        given: 'a fake process that has spoken and exited cleanly, its stdout held open as by a descendant'
        // Every step is ordered by the spec, none by the scheduler (design D16 of
        // fix-terminal-receipt-convergence; D6 of make-pipe-reads-non-parking): the drain's first
        // query takes what the parent spoke, and just before its second the holder speaks once more
        // and releases the pipe — after the exit, while the drain is still reading, which is where
        // output arriving after the process itself exited meets the capture. A holder that speaks
        // only after the drain saw the exit is dropped, as the JDK's reaper drops it (D2).
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStdout('from-the-parent\n')
        process.stdoutPipe().onQuery {
            process.stdoutPipe().onQuery {
                process.writeStdout('from-the-holder\n')
                process.release()
            }
        }
        process.exit(0)

        and: 'a runner over the default supervisor, whose post-exit bound the release falls well inside'
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(), { builder, stdin ->
            process
        } as ProcessLaunch)

        when:
        Captured captured = runner.run(new ProcessBuilder('fake'), Duration.ofSeconds(30))

        then: 'the command ran to completion'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0

        and: 'the capture is complete, including what arrived after the exit, and not flagged'
        captured.stdout() == 'from-the-parent\nfrom-the-holder\n'
        !captured.truncated()
    }

    def "design D2, NFR-P1: a real holder that writes after the exit and then ends never flags a clean exit"() {
        given: 'a binary that speaks and exits at once, leaving a real holder that speaks two seconds later'
        // The real-process twin of the feature above, with no ordering premise: which drain read
        // comes first against the exit is a scheduling fact, and the JDK keeps the holder's late
        // line only when the drain was already parked in read() — so it is not asserted. Either way
        // the pipe reaches end-of-file when the holder ends, well inside the default bound.
        Path binary = fakeBinary('trailing')
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(), ProcessLaunch.grouped())

        when:
        Captured captured = runner.run(new ProcessBuilder(binary.toString()), Duration.ofSeconds(30))

        then: 'the clean exit is reported with what the parent spoke, and nothing is flagged as cut'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0
        captured.stdout().contains('from-the-parent')
        !captured.truncated()
    }

    private static ProcessHandle holder(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }
}
