package com.github.oinsio.gnomish.e2e.paidsmoke

/**
 * Strips machine-identifying data out of a real {@code claude -p --output-format stream-json
 * --verbose} transcript before it is committed as a {@code *.reference.json} fixture (design D11's
 * "(3b) Paid smoke": "sensitive data scrubbed"; task 11.3, M4).
 *
 * <p>A real recording's stdout carries the operator's actual filesystem layout — the scratch
 * workspace's absolute path (which embeds the local username on macOS/Linux, e.g.
 * {@code /Users/<name>/...} or {@code /home/<name>/...}) appears verbatim in the {@code init}
 * event's {@code cwd} field and inside any tool_use/tool_result block that echoes a path (Read,
 * Write, Edit, Bash). None of that belongs in a file committed to the repository. Session ids are
 * also replaced: they are real CLI-issued identifiers with no fixture value of their own (the
 * existing hand-authored placeholders already use synthetic ids like {@code ref-session-plain-1},
 * so real ones are non-representative noise, not signal).
 *
 * <p>What is deliberately <em>not</em> scrubbed: resolved model ids and token counts (including
 * cache fields) — recording exactly those real values, which a local Ollama run cannot produce, is
 * this task's whole point (D11).
 *
 * <p>Implements M4, D11 of add-agent-executor.
 */
final class ReferenceDumpScrubber {

    private ReferenceDumpScrubber() {}

    /**
     * @param rawLine one raw stdout line (a single stream-json JSON object) captured from a real
     *     round
     * @param workspaceRoot the scratch workspace's absolute path, replaced everywhere it appears
     *     (as a plain string or JSON-escaped) with {@code /workspace} — matching the existing
     *     placeholder fixtures' convention
     * @param sessionId the round's real session id, replaced everywhere it appears with {@code
     *     scenarioLabel}'s derived placeholder ({@code ref-session-<scenarioLabel>-1})
     * @param scenarioLabel a short kebab-case label for the scenario (e.g. {@code plain},
     *     {@code subagent}, {@code judge}), used to build the replacement session id
     * @return {@code rawLine} with the workspace path and session id replaced; home-directory
     *     prefixes anywhere else in the line (a stray absolute path outside the workspace) are
     *     also collapsed to {@code /workspace} as defense in depth
     */
    static String scrub(String rawLine, String workspaceRoot, String sessionId, String scenarioLabel) {
        String placeholderSessionId = "ref-session-${scenarioLabel}-1"
        String scrubbed = rawLine
        scrubbed = replaceAllLiteral(scrubbed, workspaceRoot, '/workspace')
        scrubbed = replaceAllLiteral(scrubbed, jsonEscape(workspaceRoot), '/workspace')
        scrubbed = replaceAllLiteral(scrubbed, sessionId, placeholderSessionId)
        scrubbed = scrubbed.replaceAll(~/\/(Users|home)\/[^\/"\\]+/, '/workspace-scrubbed')
        return scrubbed
    }

    private static String replaceAllLiteral(String haystack, String needle, String replacement) {
        needle ? haystack.replace(needle, replacement) : haystack
    }

    private static String jsonEscape(String value) {
        value.replace('\\', '\\\\').replace('/', '\\/')
    }
}
