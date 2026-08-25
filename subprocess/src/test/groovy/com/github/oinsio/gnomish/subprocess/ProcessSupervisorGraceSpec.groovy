package com.github.oinsio.gnomish.subprocess

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D3 of bound-subprocess-commands: the kill is two-phase. Git and docker remove their lock and temporary files on a
 * catchable signal and not on SIGKILL, so everything in the tree is asked before anything is
 * forced — an immediate forcible kill leaves {@code index.lock} litter in the clone that the next
 * lifecycle push trips over. What ignores the request is still forced, at any depth.
 */
class ProcessSupervisorGraceSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def "design D3: the tree is asked cooperatively first, and only what ignores that is forced"() {
        given: 'a binary that dies on SIGTERM, over a child that records the signal and keeps running'
        // The parent lingers half a second before exiting so the assertions below read a settled
        // tree rather than a race between the child's own signal handler and the forcible phase.
        Path pidFile = dir.resolve('child.pid')
        Path binary = fakeBinary(dir, 'polite', """
trap 'sleep 0.5; exit 143' TERM
sh -c 'trap "printf term > term.marker; sleep 600" TERM; sleep 600 & wait' &
echo \$! > "\$1"
wait
""")
        Process process = new ProcessBuilder(binary.toString(), pidFile.toString())
                .directory(dir.toFile())
                .start()
        eventually('the fake binary has recorded its child pid') {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()

        and: 'a kill grace far longer than a cooperative exit needs'
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofSeconds(5))

        when:
        long startedAt = System.nanoTime()
        Supervision supervision = supervisor.await(process, Duration.ofMillis(300))
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        then: 'the outcome still names the deadline — how it was killed is not the caller\'s business'
        supervision.termination() == Termination.TIMED_OUT

        and: '128 + SIGTERM: the cooperative phase was enough for the parent, so SIGKILL (137) never happened'
        supervision.exitCode() == 143

        and: 'the cooperative signal reached the descendant too, not just the process we started'
        Files.exists(dir.resolve('term.marker'))

        and: 'NFR-R2: the descendant that ignored it was forced anyway'
        !child.isAlive()

        and: 'the grace is a bound on waiting, not a sleep: exiting early returns early'
        elapsed <Duration.ofSeconds(3)

        cleanup:
        killQuietly(process.toHandle())
        killQuietly(child)
    }
}
