package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path

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

    /** Kills a handle and everything under it, so a failed assertion never leaks a real process. */
    void killQuietly(ProcessHandle handle) {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
    }
}
