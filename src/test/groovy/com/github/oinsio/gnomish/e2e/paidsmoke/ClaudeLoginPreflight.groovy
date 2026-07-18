package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.AgentProcessLauncher
import com.github.oinsio.gnomish.adapter.agent.AgentRoundResultExtractor
import com.github.oinsio.gnomish.adapter.agent.LaunchedAgentProcess
import com.github.oinsio.gnomish.adapter.agent.StreamJsonParser
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

/**
 * Fail-fast precondition for the paid smoke task (task 11.3, M4, D11): «is `claude` logged in and
 * able to bill a real round?» rather than the Ollama layer's weaker «does the binary resolve on
 * {@code PATH}» check ({@code OllamaAvailability.claudeCliAvailable}, which is enough there only
 * because Ollama never validates the auth token).
 *
 * <p>There is no documented, side-effect-free "am I logged in" flag on the {@code claude} CLI, so
 * this class proves login the only way available: a trivial, cheap {@code claude -p} round with a
 * short timeout that must produce a real stream-json {@code result} event. Any of "binary not on
 * PATH", "process fails to start", "round times out" (typically an interactive login prompt
 * blocking on stdin), or "no result event parsed" is treated as "not logged in" and reported with
 * a specific, actionable reason — never a hang, never a bare stack trace. This trivial round is a
 * deliberate, small real spend, acceptable here because task 11.3 as a whole is designed to spend a
 * small amount of real money (M4).
 *
 * <p>Implements M4, D11, Q1 of add-agent-executor.
 */
final class ClaudeLoginPreflight {

    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(30)

    private static final String PREFLIGHT_PROMPT = 'Reply with the single word: ok'

    private ClaudeLoginPreflight() {}

    /**
     * @param binary the CLI binary name or path to check; defaults to {@code claude}
     * @param workspaceRoot an existing directory to run the preflight round in
     * @return a {@link Result} carrying either the resolved model id (proof of a working, logged-in
     *     CLI) or a human-readable reason the preflight failed — never throws
     */
    static Result check(String binary = 'claude', Path workspaceRoot) {
        def factoryProperties = new FactoryProperties('paid-smoke-preflight', binary, List.of())
        def clock = new SystemClock()
        def launcher = new AgentProcessLauncher(clock)
        DirectoryWorkspace workspace
        try {
            workspace = new DirectoryWorkspace(workspaceRoot)
        } catch (IllegalArgumentException ignored) {
            return Result.failure("workspace root is not a directory: ${workspaceRoot}")
        }

        LaunchedAgentProcess launched
        try {
            launched = launcher.launch(workspace, PREFLIGHT_PROMPT, factoryProperties)
        } catch (RuntimeException e) {
            return Result.failure("could not start '${binary}': ${e.message}")
        }
        if (launched == null) {
            return Result.failure(
                    "'${binary}' did not start — not found on PATH, or not executable. "
                    + 'Install/authenticate the Claude Code CLI (`claude login`) before running paidSmokeTest.')
        }

        List<com.github.oinsio.gnomish.adapter.agent.TimestampedEvent> events
        try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(launched.process().inputStream, StandardCharsets.UTF_8))) {
            events = new StreamJsonParser(clock).parse(reader)
        } catch (IOException e) {
            return Result.failure("could not read '${binary}' stdout: ${e.message}")
        }

        def wait = launched.waitForExitOrTimeout(PREFLIGHT_TIMEOUT, clock)
        if (wait instanceof LaunchedAgentProcess.RoundWait.TimedOut) {
            return Result.failure(
                    "'${binary}' did not finish within ${PREFLIGHT_TIMEOUT} — likely blocked on an "
                    + 'interactive login prompt. Run `claude login` manually, then retry paidSmokeTest.')
        }

        try {
            def result = new AgentRoundResultExtractor().extract(events, clock.now())
            return Result.success(result.sessionId())
        } catch (RuntimeException e) {
            return Result.failure(
                    "'${binary}' ran but produced no usable result event — not authenticated, or the "
                    + "CLI's stream-json protocol changed. Run 'claude login' and re-check manually "
                    + "before retrying. (${e.message})")
        }
    }

    /** Outcome of check: either proof of a logged-in CLI, or a specific failure reason. */
    static final class Result {

        final boolean loggedIn
        final String reason

        private Result(boolean loggedIn, String reason) {
            this.loggedIn = loggedIn
            this.reason = reason
        }

        static Result success(String sessionId) {
            new Result(true, "preflight round succeeded (session ${sessionId})")
        }

        static Result failure(String reason) {
            new Result(false, reason)
        }
    }
}
