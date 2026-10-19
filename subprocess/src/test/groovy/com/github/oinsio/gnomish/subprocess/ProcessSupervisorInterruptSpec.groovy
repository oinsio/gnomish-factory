package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, design D10 of bound-subprocess-commands: interruption is a named outcome, never an exit
 * code. The interrupt path is
 * driven deterministically by pre-interrupting the waiting thread — a wait that begins with the
 * flag already set throws at once, so nothing here depends on winning a race, which is what left
 * the five per-module copies of this catch block carrying {@code @DoNotMutate} timing-race
 * exemptions (M5). An interrupt that lands after a clean exit is placed by the latches of
 * {@link FakeProcess} instead (design D16 of fix-terminal-receipt-convergence), never by polling a
 * thread's state.
 */
class ProcessSupervisorInterruptSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def cleanup() {
        Thread.interrupted()
    }

    // Scenario: lifecycle/subprocess-supervision / The whole tree dies with the invocation
    def "FR6: an interrupted wait names interruption, restores the flag, and kills the tree"() {
        given: 'a long-lived child that ignores the cooperative signal, standing in for a wedged push'
        // It sleeps in a loop rather than waiting on one child, so that killing what it spawned is
        // not enough to end it: only the forcible phase reaching the process itself can, which is
        // the assertion below that a "the cooperative signal was enough" reading would fail.
        Path binary = fakeBinary('stubborn')
        Process process = new ProcessBuilder(binary.toString()).start()
        eventually('the fake binary is up') { process.isAlive() }

        and: 'the waiting thread is pre-interrupted, so the wait throws immediately'
        Thread.currentThread().interrupt()

        when:
        Supervision supervision = new ProcessSupervisor(Duration.ofMillis(300)).await(process, Duration.ofSeconds(30))

        then: 'the shutdown is not dressed up as a command that ran and failed'
        supervision.termination() == Termination.INTERRUPTED

        and: 'the flag is restored for callers up the stack, through every wait on the way out'
        Thread.interrupted()

        and: 'NFR-R2: a child that ignored the cooperative signal is forced rather than left running'
        eventually('the interrupted invocation\'s child is gone') {
            !process.isAlive()
        }

        cleanup:
        killQuietly(process.toHandle())
    }

    // Scenario: lifecycle/subprocess-supervision / The outcome names how the invocation ended
    def "FR6: an interrupted capture returns the named outcome with what it had captured"() {
        given: 'a binary that speaks once, leaks a holder of its stdout, and stalls'
        Path pidFile = dir.resolve('holder.pid')
        Path binary = fakeBinary('leaky')
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), pidFile.toString())
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), ProcessLaunch.grouped())

        and: 'the calling thread is interrupted before the wait begins'
        Thread.currentThread().interrupt()

        when:
        Captured captured = runner.run(builder, Duration.ofSeconds(30))

        then: 'an interrupt is its own outcome, and the capture is still handed back'
        captured.termination() == Termination.INTERRUPTED

        and: 'the flag survives the bounded drain joins too'
        Thread.interrupted()

        cleanup:
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }

    // Scenario: lifecycle/subprocess-supervision / A spec controls the exit and the pipes
    def "FR6: an interrupt landing after a clean exit is still the named outcome, never a truncated EXITED"() {
        given: 'a fake process that has spoken and exited cleanly, its stdout held and written to by a descendant'
        // Every step is ordered by the spec, none by the scheduler (G9, design D16 of
        // fix-terminal-receipt-convergence): the exit is released before the run starts, so the
        // wait observes a clean exit; the interrupt is raised on the waiting thread by the fake's
        // wait-return latch, once the drain has read what was spoken — after the exit, before the
        // join, which is where an interrupt landing after a clean exit meets the capture. The holder
        // keeps writing (D4 of make-pipe-reads-non-parking), so the drain is still open at the
        // interrupted join whatever the scheduler does; a silent one could end in between.
        FakeProcess process = FakeProcess.running()
        process.holdOpen()
        process.writeStdout('spoken')
        WritingHolder.on(process.stdoutPipe())
        process.onWaitReturn {
            if (!process.awaitStdoutRead(Duration.ofSeconds(10))) {
                throw new AssertionError('the drain never read what the process had spoken' as Object)
            }
            Thread.currentThread().interrupt()
        }
        process.exit(0)
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), { builder, stdin ->
            process
        } as ProcessLaunch)

        when:
        Captured captured = runner.run(new ProcessBuilder('fake'), null)

        then: 'the cut-short capture is not dressed up as the complete output of a finished command'
        captured.termination() == Termination.INTERRUPTED
        captured.exitCode() == 0

        and: 'what was captured before the interrupt is still handed back, flagged as a possible prefix'
        captured.stdout().startsWith('spoken')
        captured.truncated()

        and: 'the flag is restored for callers up the stack'
        Thread.interrupted()

        cleanup:
        process.release()
        Thread.interrupted()
    }

    def "FR16, design D16, FR2: a real silent holder of stdout after a clean exit lets the capture end on its own"() {
        given: 'a binary that speaks, exits cleanly, and leaves a real child holding its stdout open, writing nothing'
        // The one real-script feature design D16 keeps: it asserts only that the return came within
        // a generous bound — ten post-exit bounds — while the holder is provably alive, so no
        // scheduling order on a loaded machine can turn it red. A drain that never parks ends as soon
        // as it sees the exit (D2 of make-pipe-reads-non-parking), so nothing is cut.
        Path pidFile = dir.resolve('holder.pid')
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('exit-leaky').toString(), pidFile.toString())
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(300))

        and: 'a sweep noting whether the holder was alive when the drains had let go'
        Map<String, Boolean> atSweep = [:]
        ProcessGroupSweep sweep = new ProcessGroupSweep(new ProcessProbes(GroupedLaunch.groupSignal(), [], {
            atSweep.holderAlive = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow().isAlive()
        }))

        when:
        long startedAt = System.nanoTime()
        Captured captured = new CaptureRunner(supervisor, ProcessLaunch.grouped(), sweep).run(builder, null)

        then: 'the clean exit is returned whole and not flagged, within ten post-exit bounds'
        captured.termination() == Termination.EXITED
        captured.stdout() == 'spoken\n'
        !captured.truncated()
        Duration.ofNanos(System.nanoTime() - startedAt) <supervisor.postExitBound().multipliedBy(10)

        and: 'proof the pipe was still held when the drains let go; the group sweep ended the holder after (D19)'
        atSweep.holderAlive
        ended(pidFile)

        cleanup:
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }
}
