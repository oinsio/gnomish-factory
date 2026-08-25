package com.github.oinsio.gnomish.subprocess

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

    def "FR2, design D2: more than a pipe buffer on both streams completes with both captured in full"() {
        given: 'a binary writing ~150 KiB to each stream — well past any OS pipe buffer'
        Path binary = fakeBinary(dir, 'noisy', """
i=0
while [ \$i -lt ${LINES} ]; do
  printf '%s\\n' "\$1"
  printf '%s\\n' "\$1" >&2
  i=\$((i+1))
done
""")

        when:
        Captured captured = new CaptureRunner().run(new ProcessBuilder(binary.toString(), LINE), Duration.ofSeconds(60))

        then: 'the command completed normally rather than deadlocking on a full pipe'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 0

        and: 'both streams are captured whole, and kept apart'
        captured.stdout().length() == LINES * (LINE.length() + 1)
        captured.stderr().length() == LINES * (LINE.length() + 1)
    }

    def "design D2: on the kill path a straggler holding the pipe does not block the return"() {
        given: 'a binary that leaks a holder of its stdout out of its own process tree before stalling'
        Path pidFile = dir.resolve('holder.pid')
        Path binary = fakeBinary(dir, 'leaky', """
( sleep 30 & echo \$! > "\$1" )
echo started
sleep 600
""")

        and: 'a runner whose kill-path drain join is a fraction of what the holder will live'
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(Duration.ofMillis(300)), Duration.ofMillis(300))

        when:
        long startedAt = System.nanoTime()
        Captured captured = runner.run(new ProcessBuilder(binary.toString(), pidFile.toString()), Duration.ofSeconds(2))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the deadline is reported, with whatever the drains had read by then'
        captured.termination() == Termination.TIMED_OUT
        captured.stdout().contains('started')

        and: 'the return happened on the bound, not on the pipe closing'
        elapsed <Duration.ofSeconds(6)

        and: 'proof the pipe was still held open when the result came back'
        ProcessHandle holder = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
        holder.isAlive()

        cleanup:
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
            killQuietly(it)
        }
    }

    def "design D2, NFR-R3: a normal exit waits for its output rather than truncating it"() {
        given: 'a binary that exits at once but leaves a holder of its stdout still writing'
        Path binary = fakeBinary(dir, 'trailing', """
( sleep 1; echo from-the-holder ) &
echo from-the-parent
""")

        and: 'a runner whose kill-path drain bound is far too short to have produced that output'
        CaptureRunner runner = new CaptureRunner(new ProcessSupervisor(), Duration.ofMillis(1))

        when:
        Captured captured = runner.run(new ProcessBuilder(binary.toString()), Duration.ofSeconds(30))

        then: 'the command ran to completion, so the kill-path bound has no business here'
        captured.termination() == Termination.EXITED

        and: 'the capture is complete, including what arrived after the process itself had exited'
        captured.stdout().contains('from-the-parent')
        captured.stdout().contains('from-the-holder')
    }
}
