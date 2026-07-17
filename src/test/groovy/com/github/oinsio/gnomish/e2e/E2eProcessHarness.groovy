package com.github.oinsio.gnomish.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Spawns the real {@code gnomish run} process for the E2E layer (task 9.1, M1):
 * {@code java -jar <bootJar>} against a scripted stdin session, with stdout and
 * stderr captured separately and the exit code exposed once the process ends.
 * This is a plain OS process, not a Testcontainers scenario — {@code gnomish run}
 * is a self-contained CLI with no external service to containerize
 * ({@code .claude/rules/testing.md}'s Testcontainers guidance targets Gitea/agent
 * sandboxes elsewhere in the roadmap, not this layer).
 *
 * <p>The jar path comes from the {@code e2e.jarPath} system property, injected by
 * the {@code e2eTest} Gradle task (build.gradle), which depends on {@code bootJar}
 * so the jar always exists before this harness runs.
 *
 * <p>Scripted input lines are fed one per line, newline-terminated. By default the
 * harness closes stdin after the script is exhausted, so a too-short script
 * surfaces as real EOF (FR13, exit code 4 scenarios, task 9.3) exactly as a human
 * closing their terminal would. Pass {@code keepStdinOpen = true} for scenarios
 * that complete before the script runs out and must not race an early close
 * against the process's own exit.
 *
 * <p>M1 of add-manual-run.
 */
final class E2eProcessHarness {

    private static final String JAR_PATH_PROPERTY = 'e2e.jarPath'
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120)

    private final Path jarPath

    E2eProcessHarness() {
        String configured = System.getProperty(JAR_PATH_PROPERTY)
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
            "system property '${JAR_PATH_PROPERTY}' is not set — the e2eTest Gradle task must inject it"
            + ' (build.gradle wires it from the bootJar task output)')
        }
        Path resolved = Path.of(configured)
        if (!java.nio.file.Files.isRegularFile(resolved)) {
            throw new IllegalStateException(
            "'${JAR_PATH_PROPERTY}' points at a non-existent file: ${resolved}"
            + ' — has the bootJar task run?')
        }
        jarPath = resolved
    }

    /**
     * Spawns {@code java -jar <jar> run <extraArgs...>} with {@code workingDirectory} as the
     * process's cwd, feeds {@code scriptedInputLines} to stdin, and waits for completion.
     *
     * @param workingDirectory the process's working directory (normally irrelevant to {@code
     *     gnomish run}, which takes its workspace from {@code --project}, but set for realism)
     * @param extraArgs the {@code gnomish run} flags, e.g. {@code ['--project=...', '--task=...']}
     * @param scriptedInputLines the operator's scripted answers, one per line, in order
     * @param keepStdinOpen when {@code false} (default), stdin is closed once the script is
     *     exhausted — a too-short script becomes real EOF; when {@code true}, stdin is left open
     *     after the script so a process that finishes early does not race a stdin close against
     *     its own exit
     * @return the captured exit code, stdout, and stderr
     */
    E2eProcessResult run(
            Path workingDirectory,
            List<String> extraArgs,
            List<String> scriptedInputLines,
            boolean keepStdinOpen = false) {
        List<String> command = new ArrayList<>([
            'java',
            '-jar',
            jarPath.toAbsolutePath().toString(),
            'run'
        ])
        command.addAll(extraArgs)

        ProcessBuilder builder = new ProcessBuilder(command)
        builder.directory(workingDirectory.toFile())

        Process process = builder.start()
        ExecutorService pumps = Executors.newFixedThreadPool(3)
        try {
            // Explicit Callable<String> cast: ExecutorService#submit is overloaded for
            // Callable and Runnable, and a bare Groovy closure satisfies both — without the
            // cast, Groovy's overload resolution picks the Runnable overload here, silently
            // discarding the closure's return value and handing back a Future whose get()
            // always yields null.
            Future<String> stdoutFuture = pumps.submit({ readAll(process.inputStream) } as Callable<String>)
            Future<String> stderrFuture = pumps.submit({ readAll(process.errorStream) } as Callable<String>)
            pumps.submit({ writeStdin(process, scriptedInputLines, keepStdinOpen) } as Runnable)

            boolean finished = process.waitFor(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw new IllegalStateException(
                "gnomish run did not exit within ${DEFAULT_TIMEOUT} — command: ${command}")
            }
            return new E2eProcessResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get())
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
