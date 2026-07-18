package com.github.oinsio.gnomish.adapter.agent.fake

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Drives one fake-agent subprocess round (task 2.1, FR15, D11 of
 * add-agent-executor): a thin Groovy builder over {@link ProcessBuilder}
 * that future CLI-adapter specs (groups 6, 7) extend or call directly to
 * script a scenario, run it as a real subprocess, and read back stdout, exit
 * code, and any workspace files the scenario wrote.
 *
 * <p>This class only drives the fake; it is not itself a production
 * {@code StageExecutor}/{@code JudgeVoter} adapter. It exists so this task's
 * own specs — and later groups' specs — exercise the same real
 * ProcessBuilder/pipes/exit-code path the CLI adapters will use (D11's "one
 * suite, real machinery" scenario).
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
class FakeAgentInvocation {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30)

    /** Name of the scenario directory under {@code fake-agent/scenarios/} to play back. */
    String scenario

    /** The subprocess's working directory — the stand-in stage workspace; defaults to a fresh temp dir. */
    Path workingDirectory = Files.createTempDirectory('fake-agent-cwd')

    /**
     * When set, wired as {@code $GNOMISH_DECISION_FILE} — the scenario's
     * {@code decision.json} (if any) is copied there (D1's decision-file protocol).
     */
    Path decisionFilePath

    /** Extra CLI args appended after the script path, for ProcessBuilder-log realism only; ignored by the fake. */
    List<String> extraArgs = []

    /**
     * Runs the fake as a real subprocess and blocks for completion.
     *
     * @return the captured exit code, stdout lines, and stderr
     */
    FakeAgentResult run() {
        List<String> command = new ArrayList<>(FakeAgentBinary.commandPrefix())
        command.addAll(extraArgs)

        ProcessBuilder builder = new ProcessBuilder(command)
        builder.directory(workingDirectory.toFile())
        builder.environment().put('GNOMISH_FAKE_SCENARIO', scenario ?: '')
        if (decisionFilePath != null) {
            builder.environment().put('GNOMISH_DECISION_FILE', decisionFilePath.toAbsolutePath().toString())
        }

        Process process = builder.start()
        ExecutorService pumps = Executors.newFixedThreadPool(2)
        try {
            Future<String> stdoutFuture = pumps.submit({ readAll(process.inputStream) } as Callable<String>)
            Future<String> stderrFuture = pumps.submit({ readAll(process.errorStream) } as Callable<String>)

            boolean finished = process.waitFor(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw new IllegalStateException("fake-agent.sh did not exit within ${DEFAULT_TIMEOUT} — command: ${command}")
            }

            String stdout = stdoutFuture.get()
            List<String> lines = stdout.isEmpty() ? [] : stdout.split('\n', -1).findAll { !it.isEmpty() }.toList()
            return new FakeAgentResult(process.exitValue(), lines, stderrFuture.get())
        } finally {
            pumps.shutdownNow()
        }
    }

    private static String readAll(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }
}
