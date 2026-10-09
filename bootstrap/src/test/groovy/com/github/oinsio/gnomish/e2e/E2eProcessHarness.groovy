package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
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

    /**
     * The spawned factory's home, named through {@code GNOMISH_HOME} (design D8 of
     * add-project-registry): one temporary folder for the whole JVM, so the operator's own
     * {@code ~/.gnomish} — its host file, its projects — never reaches a spec. Every {@code --dir} a
     * spec runs against is registered here through the production {@link ProjectRegistry#add}.
     *
     * <p>It is also what keeps the spawned factory's log off the operator's file (FR11, M4 of
     * harden-logging-observability): the packaged binary carries the production Logback
     * configuration by design, which a test-classpath file cannot reach, and that configuration
     * writes where the operator configuration loader decides — under this home (FR11 of
     * add-project-registry).
     */
    static final FactoryHome HOME = FactoryHome.at(Files.createTempDirectory('gnomish-e2e-home'))

    private final Path jarPath = resolveJarPath()

    private static Path resolveJarPath() {
        String configured = System.getProperty(JAR_PATH_PROPERTY)
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
            "system property '${JAR_PATH_PROPERTY}' is not set — the e2eTest Gradle task must inject it"
            + ' (build.gradle wires it from the bootJar task output)')
        }
        Path resolved = Path.of(configured)
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException(
            "'${JAR_PATH_PROPERTY}' points at a non-existent file: ${resolved}"
            + ' — has the bootJar task run?')
        }
        return resolved
    }

    /**
     * Spawns {@code java -jar <jar> run <extraArgs...>} with {@code workingDirectory} as the
     * process's cwd, feeds {@code scriptedInputLines} to stdin, and waits for completion.
     *
     * @param workingDirectory the process's working directory (normally irrelevant to {@code
     *     gnomish run}, which takes its workspace from {@code --dir}, but set for realism)
     * @param extraArgs the {@code gnomish run} flags, e.g. {@code ['--dir=...', '--task=...']}
     * @param scriptedInputLines the operator's scripted answers, one per line, in order
     * @param keepStdinOpen when {@code false} (default), stdin is closed once the script is
     *     exhausted — a too-short script becomes real EOF; when {@code true}, stdin is left open
     *     after the script so a process that finishes early does not race a stdin close against
     *     its own exit
     * @param extraEnv variables merged into the spawned {@code gnomish run} process's environment
     *     on top of the cleared test child baseline ({@link TestChildEnvironment}) — the seam the Ollama E2E layer (task 11.1) uses to
     *     forward {@code ANTHROPIC_BASE_URL}/auth-token/model env vars down to the real {@code
     *     claude} CLI the process itself spawns: the factory's agent adapters re-set exactly these
     *     seam names from their own (the spawned JVM's) environment as factory-set protocol
     *     variables ({@code AgentAiSeam}, D6/FR9 of add-sandbox-core), so setting them on the
     *     factory process is all a spec needs
     * @return the captured exit code, stdout, and stderr
     */
    E2eProcessResult run(
            Path workingDirectory,
            List<String> extraArgs,
            List<String> scriptedInputLines,
            boolean keepStdinOpen = false,
            Map<String, String> extraEnv = [:]) {
        execute('run', workingDirectory, extraArgs, scriptedInputLines, keepStdinOpen, extraEnv)
    }

    /**
     * {@link #run} with the gnome played by {@code agentBinary}: the binary reaches the spawned
     * factory as the Spring argument {@code --factory.agent-cli-binary=<agentBinary>}, which binds
     * {@code FactoryProperties.agentCliBinary} and which {@code run}'s own parser passes through
     * as a dotted name (FR6 of remove-interactive-console). Specs hand it a fake-agent wrapper
     * from {@code FakeAgentSupport.wrapperFor}, so no round spends a token (NFR-C1).
     *
     * @param agentBinary the agent CLI the spawned factory launches for every round and vote
     */
    E2eProcessResult run(
            Path workingDirectory,
            String agentBinary,
            List<String> extraArgs,
            List<String> scriptedInputLines,
            boolean keepStdinOpen = false,
            Map<String, String> extraEnv = [:]) {
        List<String> args = [
            '--factory.agent-cli-binary=' + agentBinary
        ]
        args.addAll(extraArgs)
        execute('run', workingDirectory, args, scriptedInputLines, keepStdinOpen, extraEnv)
    }

    /**
     * {@link #run}, for any subcommand: spawns {@code java -jar <jar> <subcommand> <extraArgs...>}
     * — the seam a spec uses to drive {@code serve} or {@code take} out of process.
     *
     * @param subcommand the gnomish subcommand, e.g. {@code serve}
     */
    E2eProcessResult execute(
            String subcommand,
            Path workingDirectory,
            List<String> extraArgs,
            List<String> scriptedInputLines,
            boolean keepStdinOpen = false,
            Map<String, String> extraEnv = [:]) {
        List<String> command = new ArrayList<>([
            'java',
            '-jar',
            jarPath.toAbsolutePath().toString(),
            subcommand
        ])
        command.addAll(extraArgs)

        ProcessBuilder builder = new ProcessBuilder(command)
        builder.directory(workingDirectory.toFile())
        // Nothing inherited (design D14 of make-checkpoint-gate-durable): the spawned factory sees
        // the test child baseline, its own GNOMISH_HOME and the spec's extraEnv — never a GNOMISH_*
        // variable of the test run.
        Map<String, String> environment = TestChildEnvironment.cleared(builder)
        environment.put(FactoryHome.HOME_VARIABLE, HOME.root().toString())
        extraArgs.findAll {
            it.startsWith('--dir=')
        }.each {
            register(Path.of(it.substring('--dir='.length())))
        }
        environment.putAll(extraEnv)

        Process process = builder.start()
        ExecutorService pumps = Executors.newFixedThreadPool(3)
        try {
            // Explicit Callable<String> cast: ExecutorService#submit is overloaded for
            // Callable and Runnable, and a bare Groovy closure satisfies both — without the
            // cast, Groovy's overload resolution picks the Runnable overload here, silently
            // discarding the closure's return value and handing back a Future whose get()
            // always yields null.
            Future<String> stdoutFuture = pumps.submit({
                readAll(process.inputStream)
            } as Callable<String>)
            Future<String> stderrFuture = pumps.submit({
                readAll(process.errorStream)
            } as Callable<String>)
            pumps.submit({
                writeStdin(process, scriptedInputLines, keepStdinOpen)
            } as Runnable)

            boolean finished = process.waitFor(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw new IllegalStateException(
                "gnomish ${subcommand} did not exit within ${DEFAULT_TIMEOUT} — command: ${command}")
            }
            return new E2eProcessResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get())
        } finally {
            pumps.shutdownNow()
        }
    }

    /**
     * Registers {@code dir} in {@link #HOME} as the one clone of a project of its own, unless it
     * already is: the spawned factory refuses an unregistered clone (FR3 of add-project-registry).
     */
    private static synchronized void register(Path dir) {
        ProjectRegistry registry = ProjectRegistry.scan(HOME)
        Path clone = dir.toAbsolutePath().normalize()
        boolean registered = registry.projects().any { project ->
            project.clones().any { it.clonePath() == clone }
        }
        if (!registered) {
            registry.add(new ProjectName("e2e-${registry.projects().size() + 1}"), clone)
        }
    }

    /**
     * Registers {@code dir} like {@link #run} does and writes {@code factoryBlock} as its project's
     * {@code factory:} configuration — the only place a sandbox-boundary key such as {@code
     * factory.bindings.default} is accepted from (design D8, NFR-S1 of add-project-registry). The
     * block is appended to the {@code project.yaml} the production registry wrote; a project that
     * already carries one is refused, so two specs never merge their keys by accident.
     *
     * @param dir the clone a later {@link #run} passes as {@code --dir}
     * @param factoryBlock YAML whose top-level key is {@code factory:}
     */
    static synchronized void projectConfig(Path dir, String factoryBlock) {
        register(dir)
        Path file = ProjectRegistry.scan(HOME).resolve(dir.toAbsolutePath().normalize()).layout().config()
        String text = Files.readString(file)
        assert !(text =~ /(?m)^factory:/): "${file} already carries a factory: block"
        Files.writeString(file, (text.endsWith('\n') ? text : text + '\n') + factoryBlock)
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
