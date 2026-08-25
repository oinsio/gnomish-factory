package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ExecHandle
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, G5, NFR-R2 of bound-subprocess-commands: a round that expires takes the agent CLI's whole
 * process tree with it. Destroying the parent alone — what this handle did before — left an agent
 * CLI's own subprocesses running after the round that launched them had been declared timed out,
 * so a long day of timeouts accumulated orphans nobody was accounting for.
 *
 * <p>Real processes throughout: the subject is what the OS does with a fork that ignores a
 * cooperative signal, and none of that survives being mocked.
 */
class HostExecHandleTreeKillSpec extends Specification {

    private final Clock clock = { -> Instant.now() } as Clock

    @TempDir
    Path tempDir

    Process process
    ProcessHandle child

    def cleanup() {
        Thread.interrupted() // never leak an interrupt flag into the next feature
        child?.descendants()?.forEach { it.destroyForcibly() }
        child?.destroyForcibly()
        process?.descendants()?.forEach { it.destroyForcibly() }
        process?.destroyForcibly()
    }

    // FR11, G5, M2: the timeout kill is a tree kill — the CLI's children do not outlive the round
    def "FR11, G5: a timed-out round leaves no orphaned agent children"() {
        given: 'a fake agent CLI that forks a child of its own and then outlives any round budget'
        def pidFile = tempDir.resolve('child.pid')
        def cli = tempDir.resolve('fake-agent-cli')
        Files.writeString(cli, """#!/bin/sh
sleep 600 &
echo \$! > "\$1"
wait
""")
        cli.toFile().setExecutable(true)
        process = new ProcessBuilder(cli.toString(), pidFile.toString()).start()
        def handle = new HostExecHandle(process, Instant.now())

        and: 'the child really exists before the round is cut short'
        eventually('the fake agent CLI has recorded its child pid') {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()

        when: 'the round timeout expires'
        def wait = handle.waitForExitOrTimeout(Duration.ofMillis(300), clock)

        then: 'the round reports the timeout'
        wait instanceof ExecHandle.Wait.TimedOut

        and: 'and neither the CLI nor the child it spawned survives it'
        !process.isAlive()
        !child.isAlive()
    }

    // FR6, FR11: an interrupted wait is a named outcome, and it kills the same tree
    def "FR6, FR11: an interrupted wait is named, not coded, and still kills the tree"() {
        given: 'a process that would far outlive the round'
        process = new ProcessBuilder('sleep', '600').start()
        def handle = new HostExecHandle(process, Instant.now())

        when: 'the wait is interrupted before it begins, which drives the path deterministically'
        Thread.currentThread().interrupt()
        def wait = handle.waitForExitOrTimeout(Duration.ofMinutes(10), clock)

        then: 'the outcome names the interruption rather than blaming the round budget'
        wait instanceof ExecHandle.Wait.Interrupted

        and: 'the process was killed, not left running behind the shutdown'
        !process.isAlive()

        and: 'the caller up the stack still sees the interrupt'
        Thread.interrupted()
    }

    private static void eventually(String what, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() <deadline) {
            if (condition.call()) {
                return
            }
            Thread.sleep(20)
        }
        throw new AssertionError("timed out waiting until ${what}" as Object)
    }
}
