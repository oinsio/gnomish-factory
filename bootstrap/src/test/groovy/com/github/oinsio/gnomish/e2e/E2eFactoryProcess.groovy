package com.github.oinsio.gnomish.e2e

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * One live packaged factory process the {@link E2eProcessHarness} started: its stdout and stderr
 * captured separately, its scripted stdin fed, and its end — awaited, or forced as a crash would end
 * it. The harness composes the command and the environment; this owns the process from its start.
 *
 * <p>{@link #killed} is the {@code SIGKILL} of the process kill-point specs need out of process
 * ({@code .claude/rules/crash-consistency.md}, item 10): the factory and every process it started —
 * a git push and the hook it waits on, a {@code docker exec} — die without a {@code finally} block,
 * so the durable media keep exactly what the factory had made durable before the kill.
 *
 * <p>M1 of add-manual-run; M3 of migrate-verify-to-executor-contract (task 7.1).
 */
final class E2eFactoryProcess {

    private final Process process
    private final List<String> command
    private final ExecutorService pumps = Executors.newFixedThreadPool(3)
    private final Future<String> stdout
    private final Future<String> stderr

    private E2eFactoryProcess(Process process, List<String> command, List<String> stdinLines, boolean keepStdinOpen) {
        this.process = process
        this.command = command
        // Explicit Callable<String> cast: ExecutorService#submit is overloaded for Callable and
        // Runnable, and a bare Groovy closure satisfies both — without the cast, Groovy's overload
        // resolution picks the Runnable overload, and the Future's get() always yields null.
        this.stdout = pumps.submit({
            readAll(process.inputStream)
        } as Callable<String>)
        this.stderr = pumps.submit({
            readAll(process.errorStream)
        } as Callable<String>)
        pumps.submit({
            writeStdin(process, stdinLines, keepStdinOpen)
        } as Runnable)
    }

    /**
     * Starts {@code builder}'s process and its pumps.
     *
     * @param stdinLines the operator's scripted answers, one per line, in order
     * @param keepStdinOpen when {@code false}, stdin is closed once the script is exhausted — a
     *     too-short script becomes real EOF; when {@code true}, it is left open
     */
    static E2eFactoryProcess start(ProcessBuilder builder, List<String> stdinLines, boolean keepStdinOpen) {
        new E2eFactoryProcess(builder.start(), builder.command(), stdinLines, keepStdinOpen)
    }

    /** Whether the process is still running. */
    boolean alive() {
        process.isAlive()
    }

    /**
     * Waits for the process to end on its own.
     *
     * @throws IllegalStateException when it has not ended within {@code timeout}; it is killed first
     */
    E2eProcessResult await(Duration timeout) {
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            killed()
            throw new IllegalStateException("gnomish did not exit within ${timeout} — command: ${command}")
        }
        result()
    }

    /**
     * Kills the process and every process it started, as a {@code SIGKILL} of the factory and a
     * host that reaps its orphans would: the descendants are listed before the factory dies, since
     * its orphans are re-parented away from it once it is gone.
     *
     * @return what the process wrote before its death; its exit code is the kill's
     */
    E2eProcessResult killed() {
        List<ProcessHandle> descendants = process.descendants().toList()
        process.destroyForcibly()
        process.waitFor()
        descendants.each { it.destroyForcibly() }
        descendants.each { it.onExit().get(60, TimeUnit.SECONDS) }
        result()
    }

    private E2eProcessResult result() {
        try {
            new E2eProcessResult(process.exitValue(), stdout.get(60, TimeUnit.SECONDS), stderr.get(60, TimeUnit.SECONDS))
        } finally {
            pumps.shutdownNow()
        }
    }

    private static void writeStdin(Process process, List<String> lines, boolean keepOpen) {
        OutputStream stdin = process.outputStream
        try {
            for (String line : lines) {
                stdin.write((line + '\n').getBytes(StandardCharsets.UTF_8))
                stdin.flush()
            }
            if (!keepOpen) {
                stdin.close()
            }
        } catch (IOException ignored) {
            // The process may have already exited (e.g. usage/pipeline-load errors before any
            // prompt) and closed its side of the pipe — nothing left to feed, nothing to report.
        }
    }

    private static String readAll(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }
}
