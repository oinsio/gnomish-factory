package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Hands out the committed process-shaped fakes the supervisor is pointed at (the stand-in library's
 * {@code process/} scripts, ADR 0015). Real processes, not fakes: the whole subject here is what
 * the OS does with a subprocess that stalls, forks, ignores a signal, or keeps a pipe open, and
 * none of that survives being mocked. Each script is committed and reviewed once; a spec names it.
 */
trait FakeBinaries {

    /** The committed fake {@code name}, run as it is; its header says what it does with its arguments. */
    Path fakeBinary(String name) {
        StandIn.process(name)
    }

    /**
     * Polls {@code condition} until it holds, up to ten seconds. Only ever used to wait for
     * something the fake binary does on its own schedule (writing a pid file, forking a child) —
     * never to paper over the supervisor's own timing, which every spec bounds explicitly.
     */
    void eventually(String what, Closure<Boolean> condition) {
        long deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() <deadline) {
            if (condition.call()) {
                return
            }
            Thread.sleep(20)
        }
        throw new AssertionError("timed out waiting until ${what}" as Object)
    }

    /**
     * Whether the process in {@code pidFile} has ended, waiting up to ten seconds for its exit — the
     * group sweep's kill reaches it at once, its reap by {@code init} a moment after.
     */
    boolean ended(Path pidFile) {
        long pid = Long.parseLong(Files.readString(pidFile).trim())
        ProcessHandle.of(pid).ifPresent {
            it.onExit().get(10, TimeUnit.SECONDS)
        }
        !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)
    }

    /** Kills a handle and everything under it, so a failed assertion never leaks a real process. */
    void killQuietly(ProcessHandle handle) {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
    }

    /**
     * A child on the test baseline whose {@code PATH} is {@code first} ahead of the baseline's own,
     * so the recording launcher — a shell script — still finds its tools, and a binary resolved
     * from the baseline would show up in the launcher's log.
     */
    ProcessBuilder onPath(String first, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
        TestChildEnvironment.cleared(builder)
        String baseline = builder.environment().get('PATH')
        builder.environment().put('PATH', baseline == null ? first : "${first}:${baseline}".toString())
        builder
    }
}
