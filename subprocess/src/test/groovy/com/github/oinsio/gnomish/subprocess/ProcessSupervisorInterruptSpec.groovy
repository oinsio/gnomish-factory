package com.github.oinsio.gnomish.subprocess

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
 * exemptions (M5).
 */
class ProcessSupervisorInterruptSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def cleanup() {
        Thread.interrupted()
    }

    def "FR6: an interrupted wait names interruption, restores the flag, and kills the tree"() {
        given: 'a long-lived child that ignores the cooperative signal, standing in for a wedged push'
        // It sleeps in a loop rather than waiting on one child, so that killing what it spawned is
        // not enough to end it: only the forcible phase reaching the process itself can, which is
        // the assertion below that a "the cooperative signal was enough" reading would fail.
        Path binary = fakeBinary(dir, 'stubborn', """
trap '' TERM
while :; do sleep 1; done
""")
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

    def "FR6: an interrupted capture returns the named outcome with what it had captured"() {
        given: 'a binary that speaks once, leaks a holder of its stdout, and stalls'
        Path pidFile = dir.resolve('holder.pid')
        Path binary = fakeBinary(dir, 'leaky', """
( sleep 30 & echo \$! > "\$1" )
echo started
sleep 600
""")
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), pidFile.toString())
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), Duration.ofMillis(300))

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

    def "FR6: an interrupt landing after a clean exit is still the named outcome, never a truncated EXITED"() {
        given: 'a binary that speaks, exits cleanly, and leaves a child holding its stdout open'
        Path pidFile = dir.resolve('holder.pid')
        Path binary = fakeBinary(dir, 'exit-leaky', """
echo spoken
sleep 600 &
echo \$! > "\$1"
exit 0
""")
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), pidFile.toString())
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), Duration.ofMillis(300))

        and: 'a helper that interrupts the capture once it parks in the drain join the holder blocks'
        // The wait itself is a timed park (TIMED_WAITING); the only untimed WAITING on the path is
        // the unbounded drain join after a clean exit, so the state transition is the deterministic
        // "the process has exited, the join has begun" signal.
        Thread waiter = Thread.currentThread()
        Thread helper = Thread.start {
            while (waiter.state != Thread.State.WAITING) {
                Thread.sleep(10)
            }
            waiter.interrupt()
        }

        when:
        Captured captured = runner.run(builder, Duration.ofSeconds(30))

        then: 'the cut-short capture is not dressed up as the complete output of a finished command'
        captured.termination() == Termination.INTERRUPTED

        and: 'what was captured before the interrupt is still handed back, with the flag restored'
        captured.stdout().contains('spoken')
        Thread.interrupted()

        cleanup:
        helper.join()
        Thread.interrupted()
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }
}
