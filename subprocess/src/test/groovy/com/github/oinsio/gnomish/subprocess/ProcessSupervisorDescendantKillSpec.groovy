package com.github.oinsio.gnomish.subprocess

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, design D14 of bound-subprocess-commands: the shutdown shape of the kill — everything a parent started dies, at any
 * depth and whether or not it takes the cooperative hint, while the parent itself is left running.
 * This is what the daemon's process-tree killer runs on at exit, where the JVM is the parent and
 * must obviously survive its own shutdown hook.
 */
class ProcessSupervisorDescendantKillSpec extends Specification implements FakeBinaries {

    @TempDir
    Path dir

    def "FR14: every descendant dies and is reaped, while the parent is left alone"() {
        given: 'a binary that forks a signal-ignoring child of its own and then blocks on stdin'
        Path pidFile = dir.resolve('child.pid')
        // `wait` and `read` are shell builtins, so the parent itself blocks without forking a
        // helper: every descendant below is one the kill is supposed to reach, none is collateral.
        // The `wait` is what makes the parent reap the killed child: dash (Linux /bin/sh), unlike
        // bash, does not reap a background child while blocked in `read`, and an unreaped zombie
        // still reports alive through ProcessHandle — in production the parent is the JVM, whose
        // process reaper does this for every child it spawned. After `wait` returns, `read` keeps
        // the parent itself alive for the FR14 assertion.
        Path binary = fakeBinary(dir, 'parent', """
sh -c 'trap "" TERM; sleep 600' &
echo \$! > "\$1"
wait
read line
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

        when: 'the parent asks that nothing it started outlive it'
        new ProcessSupervisor(Duration.ofMillis(300)).terminateDescendants(process.toHandle())

        then: 'NFR-R2: nothing below the parent is left running, at any depth — and it is reaped'
        !child.isAlive()
        grandchildren.every { !it.isAlive() }

        and: 'FR14: the parent itself was never signalled'
        process.isAlive()

        cleanup:
        killQuietly(process.toHandle())
        killQuietly(child)
        grandchildren.each { killQuietly(it) }
    }

    def "FR14: a parent with nothing below it is a no-op, not a failure"() {
        given: 'a short-lived process that forks nothing'
        Process process = new ProcessBuilder('true').start()

        when: 'the daemon exit path runs against it — the way it runs most of the time'
        new ProcessSupervisor(Duration.ofMillis(50)).terminateDescendants(process.toHandle())

        then: 'no work, no exception, and the process itself is untouched'
        noExceptionThrown()
    }
}
