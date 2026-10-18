package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, design D14 of bound-subprocess-commands: the shutdown shape of the kill — everything a parent started dies, at any
 * depth and whether or not it takes the cooperative hint, while the parent itself is left running.
 * This is what the daemon's process-tree killer runs on at exit, where the JVM is the parent and
 * must obviously survive its own shutdown hook.
 *
 * <p>FR21, design D4 and D19 of fix-terminal-receipt-convergence: that snapshot kill is kept for
 * the cooperative phase, and it is a backstop over what the snapshot sees, not the owner of the
 * orphans — a member of a launch's group that the child lost to reparenting is in no snapshot, and
 * the group sweep its waiter runs once the wait resolves after the kill is what ends it.
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
        Path binary = fakeBinary('parent')
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

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21: the shutdown kill misses an orphan reparented out of the child's tree, and the waiter's group sweep after it ends it"() {
        given: 'a waiter capturing leaky through the launch owner: a holder of its stdout leaked out of its tree, then a stall'
        Path pidFile = dir.resolve('holder.pid')
        ProcessSupervisor supervisor = new ProcessSupervisor(Duration.ofMillis(300))
        ProcessLaunch grouped = ProcessLaunch.grouped(dir)
        CompletableFuture<Process> launched = new CompletableFuture<>()
        ProcessLaunch recording = { ProcessBuilder command, StdinSource stdin ->
            Process started = grouped.start(command, stdin)
            launched.complete(started)
            started
        } as ProcessLaunch
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('leaky').toString(), pidFile.toString())
        TestChildEnvironment.cleared(builder)
        CompletableFuture<Captured> waiter = CompletableFuture.supplyAsync {
            new CaptureRunner(supervisor, recording).run(builder, null)
        }

        and: 'the holder is up and has left the child\'s tree; the child stalls in a sleep of its own'
        ProcessHandle child = launched.get(10, TimeUnit.SECONDS).toHandle()
        eventually('the fake binary has recorded the holder') {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        ProcessHandle holder = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
        eventually('the holder has been reparented and the child has forked its sleep') {
            List<Long> below = child.descendants().map { it.pid() }.toList()
            !below.isEmpty() && !below.contains(holder.pid())
        }

        expect: 'no snapshot from the JVM down lists the holder'
        holder.isAlive()
        ProcessHandle.current().descendants().noneMatch {
            it.pid() == holder.pid()
        }

        when: 'the factory\'s shutdown kill runs over everything the JVM started'
        supervisor.terminateDescendants(ProcessHandle.current())
        Captured captured = waiter.get(30, TimeUnit.SECONDS)

        then: 'the snapshot kill ended the child with its cooperative signal, and that is the result'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 143
        captured.stdout().contains('started')

        and: 'the waiter swept the group once its wait resolved, and the holder is gone'
        captured.sweep().foundSurvivors()
        ended(pidFile)

        cleanup:
        child?.with { killQuietly(it) }
        holder?.with { killQuietly(it) }
    }
}
