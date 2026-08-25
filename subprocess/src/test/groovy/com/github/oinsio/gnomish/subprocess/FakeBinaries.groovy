package com.github.oinsio.gnomish.subprocess

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes throwaway {@code /bin/sh} scripts to stand in for the real binaries the supervisor is
 * pointed at. Real processes, not fakes: the whole subject here is what the OS does with a
 * subprocess that stalls, forks, ignores a signal, or keeps a pipe open, and none of that survives
 * being mocked.
 */
trait FakeBinaries {

    /** Writes an executable {@code sh} script named {@code name} into {@code dir}. */
    Path fakeBinary(Path dir, String name, String body) {
        Path script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n${body}\n")
        script.toFile().setExecutable(true)
        return script
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
