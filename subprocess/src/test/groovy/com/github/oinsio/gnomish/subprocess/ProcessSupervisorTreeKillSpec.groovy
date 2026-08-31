package com.github.oinsio.gnomish.subprocess

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-R2, G5, M2 of bound-subprocess-commands: the kill reaches the whole process tree, not
 * just the process the factory started. A timed-out agent CLI whose parent alone was destroyed left its children running — the
 * latent leak this closes — and a tree that keeps forking while it is being killed is still
 * finished off, because the forcible phase re-snapshots rather than trusting the first look.
 */
class ProcessSupervisorTreeKillSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def "FR3, NFR-R2, G5, M2: neither the child nor its descendants survive a deadline kill"() {
        given: 'a binary that ignores the cooperative signal and forks a child that ignores it too'
        Path pidFile = dir.resolve('child.pid')
        Path binary = fakeBinary(dir, 'tree', """
trap '' TERM
sh -c 'trap "" TERM; sleep 600' &
echo \$! > "\$1"
wait
""")
        Process process = new ProcessBuilder(binary.toString(), pidFile.toString()).start()

        and: 'the tree is fully up: the forked child exists and has forked its own sleep'
        eventually('the fake binary has recorded its child pid') {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
        eventually('the child has forked a grandchild') {
            !child.descendants().toList().isEmpty()
        }
        List<ProcessHandle> grandchildren = child.descendants().toList()

        when: 'the deadline expires against a tree that will not take a hint'
        // The grace bounds both the cooperative wait and the post-SIGKILL reap; at 300ms a
        // loaded CI runner can miss the reap and report -1 instead of the exit code.
        Supervision supervision = new ProcessSupervisor(Duration.ofSeconds(2)).await(process, Duration.ofSeconds(2))

        then: 'the outcome names the deadline'
        supervision.termination() == Termination.TIMED_OUT

        and: 'M2: nothing the invocation started is left running, at any depth — and it is reaped'
        !process.isAlive()
        !child.isAlive()
        grandchildren.every { !it.isAlive() }

        and: 'the signal-ignoring parent was forced, not merely asked (128 + SIGKILL)'
        supervision.exitCode() == 137

        cleanup:
        killQuietly(process.toHandle())
        killQuietly(child)
        grandchildren.each { killQuietly(it) }
    }

    def "design D3: a child forked while the tree is being killed is caught by the re-snapshot"() {
        given: 'a binary that answers the cooperative signal by forking a fresh child and carrying on'
        Path latePidFile = dir.resolve('late.pid')
        Path binary = fakeBinary(dir, 'late', """
trap 'sh -c "trap \\"\\" TERM; sleep 600" & echo \$! > "\$1"; wait' TERM
sleep 600 &
wait
""")
        Process process = new ProcessBuilder(binary.toString(), latePidFile.toString()).start()
        eventually('the fake binary is up') { process.isAlive() }

        when: 'the deadline expires, so the late child appears inside the kill grace'
        Supervision supervision = new ProcessSupervisor(Duration.ofSeconds(2)).await(process, Duration.ofSeconds(1))

        then:
        supervision.termination() == Termination.TIMED_OUT

        and: 'the late child was born after the first snapshot — only the re-snapshot can see it'
        eventually('the late child pid has been recorded') {
            Files.exists(latePidFile) && !Files.readString(latePidFile).isBlank()
        }
        ProcessHandle late = ProcessHandle.of(Long.parseLong(Files.readString(latePidFile).trim())).orElse(null)

        and: 'NFR-R2: and it is dead all the same'
        late == null || !late.isAlive()

        cleanup:
        killQuietly(process.toHandle())
        if (Files.exists(latePidFile) && !Files.readString(latePidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(latePidFile).trim())).ifPresent {
                killQuietly(it)
            }
        }
    }
}
