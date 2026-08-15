package com.github.oinsio.gnomish.e2e.paidsmoke

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Strips machine-identifying and sensitive data out of a real {@code claude -p --output-format
 * stream-json --verbose} transcript before it is committed as a {@code *.reference.json} fixture
 * (design D11's "(3b) Paid smoke": "sensitive data scrubbed"; task 11.3, M4). Scrubbing runs in two
 * passes: literal string replacement, then recursive JSON deny-list pruning.
 *
 * <p><strong>Stripped:</strong>
 * <ul>
 *   <li>workspace absolute paths — the scratch workspace root (which embeds the local username on
 *       macOS/Linux, appearing verbatim in the {@code init} event's {@code cwd} and in any
 *       tool_use/tool_result block that echoes a path) is replaced, in both plain and JSON-escaped
 *       form, with {@code /workspace};</li>
 *   <li>machine- or user-identifying absolute temp paths and their dashed project-dir encoding —
 *       stray {@code /Users/...} or {@code /home/...} prefixes, the macOS per-user temp root
 *       {@code /private/var/folders/<xx>/<hash>...} (and {@code /var/folders/...}), the per-uid
 *       {@code /private/tmp/claude-<uid>...} (and {@code /tmp/claude-<uid>...}), and the
 *       slash-to-dash encoded form Claude Code derives for its project directory
 *       ({@code -private-var-folders-...}) — each collapsed to {@code /workspace-scrubbed}
 *       ({@code -workspace-scrubbed} for the dashed form) as defense in depth;</li>
 *   <li>real session ids — replaced with the synthetic placeholder {@code ref-session-<label>-1};</li>
 *   <li>money — {@code total_cost_usd} and every nested {@code costUSD};</li>
 *   <li>the {@code permission_denials} array — it carries raw tool inputs;</li>
 *   <li>every per-event {@code uuid} and {@code request_id}.</li>
 * </ul>
 *
 * <p><strong>Kept</strong> (deliberately, for D11 realism): resolved model ids and all token/cache
 * counts (top-level {@code usage} and per-model {@code modelUsage}) — recording exactly those real
 * values, which a local Ollama run cannot produce, is this task's whole point; also durations, API
 * message ids and the {@code tool_use_id} linkage, {@code result} text, and the overall event shape.
 * Opaque per-run identifiers that carry no machine or user identity — {@code agentId},
 * {@code task_id}, the random {@code workspaceRoot<n>} temp suffix — are kept for the same reason
 * (they are representative CLI output, like the API message ids; harden-reference-dump-scrubber NG3).
 *
 * <p>The deny-list ({@code total_cost_usd}, {@code costUSD}, {@code permission_denials},
 * {@code uuid}, {@code request_id}) is the single place to extend when a future CLI version adds a
 * new sensitive field — that keeps the design risk of "deny-list drift" contained to one edit.
 *
 * <p>Implements M4, D11 of add-agent-executor.
 * <p>Implements NFR-O1 of harden-reference-dump-scrubber.
 */
final class ReferenceDumpScrubber {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    private static final Set<String> DENY_LIST = [
        'total_cost_usd',
        'costUSD',
        'permission_denials',
        'uuid',
        'request_id',
    ].toSet()

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
     * @return {@code rawLine} with the workspace path and session id replaced and machine- or
     *     user-identifying temp paths (home dirs, {@code var/folders} hashes, {@code tmp/claude-<uid>}
     *     dirs, and their dashed project-dir encoding) collapsed to {@code /workspace-scrubbed}, then,
     *     when the line parses as JSON, every deny-listed field recursively pruned; if it does not
     *     parse, the string-scrubbed line is returned as-is
     */
    static String scrub(String rawLine, String workspaceRoot, String sessionId, String scenarioLabel) {
        String placeholderSessionId = "ref-session-${scenarioLabel}-1"
        String scrubbed = rawLine
        scrubbed = replaceAllLiteral(scrubbed, workspaceRoot, '/workspace')
        scrubbed = replaceAllLiteral(scrubbed, jsonEscape(workspaceRoot), '/workspace')
        scrubbed = replaceAllLiteral(scrubbed, sessionId, placeholderSessionId)
        scrubbed = collapseMachinePaths(scrubbed)
        try {
            JsonNode tree = MAPPER.readTree(scrubbed)
            pruneDenyListed(tree)
            return MAPPER.writeValueAsString(tree)
        } catch (Exception ignored) {
            return scrubbed
        }
    }

    /**
     * Ordered defense-in-depth path collapses, run after the literal path/session replacement.
     * Each rewrites a machine- or user-identifying absolute path (or Claude Code's dashed
     * project-dir encoding of one) to a stable {@code /workspace-scrubbed} placeholder. Idempotent:
     * the placeholder matches none of these patterns, so a second pass is a no-op (NFR-R1). The
     * random {@code workspaceRoot<n>} temp suffix rides inside the {@code var-folders} segment and
     * is removed with it; the opaque {@code agentId}/{@code task_id} tokens carry no machine or user
     * identity and are deliberately left intact (NG3).
     */
    private static String collapseMachinePaths(String line) {
        String out = line
        out = out.replaceAll(~/\/(Users|home)\/[^\/"\\]+/, '/workspace-scrubbed')
        out = out.replaceAll(~/(\/private)?\/tmp\/claude-\d+/, '/workspace-scrubbed')
        out = out.replaceAll(~/(\/private)?\/var\/folders\/[^\/"\\]+\/[^\/"\\]+/, '/workspace-scrubbed')
        out = out.replaceAll(~/-(private-)?var-folders-[^\/"\\]*/, '-workspace-scrubbed')
        out
    }

    private static void pruneDenyListed(JsonNode node) {
        if (node instanceof ObjectNode) {
            node.remove(DENY_LIST)
            for (JsonNode child : node) {
                pruneDenyListed(child)
            }
        } else if (node instanceof ArrayNode) {
            for (JsonNode element : node) {
                pruneDenyListed(element)
            }
        }
    }

    private static String replaceAllLiteral(String haystack, String needle, String replacement) {
        needle ? haystack.replace(needle, replacement) : haystack
    }

    private static String jsonEscape(String value) {
        value.replace('\\', '\\\\').replace('/', '\\/')
    }
}
