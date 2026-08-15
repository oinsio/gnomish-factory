package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.adapter.agent.AgentRoundResultExtractor
import com.github.oinsio.gnomish.adapter.agent.StreamJsonParser
import com.github.oinsio.gnomish.adapter.agent.TimestampedEvent
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Fail-fast precondition for the paid smoke task (task 11.3, M4, D11): «is `claude` logged in and
 * able to bill a real round?» rather than the Ollama layer's weaker «does the binary resolve on
 * {@code PATH}» check ({@code OllamaAvailability.claudeCliAvailable}, which is enough there only
 * because Ollama never validates the auth token).
 *
 * <p>There is no documented, side-effect-free "am I logged in" flag on the {@code claude} CLI, so
 * this class proves login the only way available: a trivial, cheap {@code claude -p} round (prompt
 * on stdin — FR24, D18 of add-sandbox-core) with a short timeout that must produce a real
 * stream-json {@code result} event. The round runs through the {@code TaskExecutionEnvironment}
 * port, the same seam {@code CliStageExecutor}/{@code CliJudgeVoter} use. Any of "binary not on
 * PATH", "process fails to start", "round times out" (typically an interactive login prompt
 * blocking on stdin), or "no result event parsed" is treated as "not logged in" and reported with a
 * specific, actionable reason — never a hang, never a bare stack trace.
 *
 * <p>Implements M4, D11, Q1 of add-agent-executor; FR4, FR24 of add-sandbox-core.
 */
final class ClaudeLoginPreflight {

    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(30)

    private static final String PREFLIGHT_PROMPT = 'Reply with the single word: ok'

    private ClaudeLoginPreflight() {}

    /**
     * @param binary the CLI binary name or path to check; defaults to {@code claude}
     * @param workspaceRoot an existing directory to run the preflight round in
     * @return a {@link Result} carrying either the resolved session id (proof of a working, logged-in
     *     CLI) or a human-readable reason the preflight failed — never throws
     */
    static Result check(String binary = 'claude', Path workspaceRoot) {
        def clock = new SystemClock()
        if (!Files.isDirectory(workspaceRoot)) {
            return Result.failure("workspace root is not a directory: ${workspaceRoot}")
        }
        ExecHandle launched
        try {
            launched = PaidSmokeAgentLauncher.launch(binary, workspaceRoot, clock, PREFLIGHT_PROMPT)
        } catch (ProcessStartException e) {
            return Result.failure(
                    "'${binary}' did not start — not found on PATH, or not executable. "
                    + "Install/authenticate the Claude Code CLI (`claude login`) before running paidSmokeTest. "
                    + "(${e.message})")
        }

        List<TimestampedEvent> events
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(launched.output(), StandardCharsets.UTF_8))) {
            events = new StreamJsonParser(clock).parse(reader)
        } catch (IOException e) {
            return Result.failure("could not read '${binary}' stdout: ${e.message}")
        }

        def wait = launched.waitForExitOrTimeout(PREFLIGHT_TIMEOUT, clock)
        if (wait instanceof ExecHandle.Wait.TimedOut) {
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
