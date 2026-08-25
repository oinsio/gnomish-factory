package com.github.oinsio.gnomish.subprocess

import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-R1, M1 of bound-subprocess-commands: a supervised invocation with a deadline returns on
 * that deadline whatever the child does — the reproduced defect being a git push against a silent
 * remote that never returned at all, because the caller blocked reading a stdout the child never
 * closed.
 */
class ProcessSupervisorStallSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def "FR3, NFR-R1, M1: a child that stalls holding its output open cannot outlive its deadline"() {
        given: 'a binary that produces no exit and holds stdout open through a child of its own'
        Path binary = fakeBinary(dir, 'stall', 'sleep 600')
        Process process = new ProcessBuilder(binary.toString()).start()

        and: 'a supervisor with a two-second deadline — the child would run for ten minutes'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(300))

        when:
        long startedAt = System.nanoTime()
        Supervision supervision = supervisor.await(process, Duration.ofSeconds(2))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the outcome names the deadline rather than dressing it as an exit code'
        supervision.termination() == Termination.TIMED_OUT

        and: 'M1: the wall clock stays inside twice the configured deadline'
        elapsed <Duration.ofSeconds(4)

        and: 'the process is gone and reaped, not merely signalled'
        !process.isAlive()

        cleanup:
        killQuietly(process.toHandle())
    }

    def "FR6: a command that exits on its own is untouched by the deadline"() {
        given: 'a binary that exits promptly, well inside the deadline'
        Path binary = fakeBinary(dir, 'quick', 'exit 3')
        Process process = new ProcessBuilder(binary.toString()).start()

        when:
        Supervision supervision = new ProcessSupervisor().await(process, Duration.ofSeconds(30))

        then: 'NFR-R3: the exit code is the process\'s own, under an EXITED termination'
        supervision == new Supervision(Termination.EXITED, 3)
    }

    def "NG3: a command supervised without a deadline waits as long as it takes"() {
        given: 'a binary that exits after a beat — the local-command shape, deliberately unbounded'
        Path binary = fakeBinary(dir, 'local', 'sleep 0.2; exit 7')
        Process process = new ProcessBuilder(binary.toString()).start()

        when:
        Supervision supervision = new ProcessSupervisor().await(process, null)

        then:
        supervision == new Supervision(Termination.EXITED, 7)
    }
}
