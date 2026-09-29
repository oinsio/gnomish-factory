package com.github.oinsio.gnomish.adapter.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the argv for one {@code claude -p} round: binary, {@code -p} (print
 * mode, with the prompt delivered on stdin, never as an argument — FR24, D18 of
 * add-sandbox-core), the caller's already-rendered invocation-flags segment, and
 * the hard-wired print-mode transport flags that are protocol internals, not
 * settings (FR12) — {@code --output-format stream-json --verbose}. The prompt
 * itself travels through {@link
 * com.github.oinsio.gnomish.sandbox.ExecCommand#stdin()} so a large
 * accumulated-feedback prompt cannot hit the platform's single-argument size
 * limit and is not exposed in process listings.
 *
 * <p>Beside the transport flags sit the two permission-policy flags every round carries:
 * {@code --permission-mode} with the token its {@link AgentRole} owns (design D1), and
 * {@code --strict-mcp-config} with no {@code --mcp-config}, which excludes every MCP server —
 * the operator's own and a {@code .mcp.json} in the working copy alike — from executor rounds
 * and judge votes, so none starts a process or adds its tool definitions to the round's
 * starting context (design D2). Both come from this one place, and the role is a required
 * parameter: no argv exists without a mode.
 *
 * <p>Implements FR1, FR3, NFR-S1 of fix-oversized-adapters; FR24 of add-sandbox-core;
 * FR1, FR2, FR3, FR4, NFR-S1, NFR-S2, NFR-C1 of fix-operator-blockers.
 */
final class AgentCommandLine {

    private static final String PRINT_FLAG = "-p";

    private static final String OUTPUT_FORMAT_FLAG = "--output-format";

    private static final String STREAM_JSON = "stream-json";

    private static final String VERBOSE_FLAG = "--verbose";

    private static final String PERMISSION_MODE_FLAG = "--permission-mode";

    private static final String STRICT_MCP_CONFIG_FLAG = "--strict-mcp-config";

    private AgentCommandLine() {}

    /**
     * Command with the invocation flags already rendered by the caller, inserted
     * verbatim after {@code -p}, followed by the role's permission mode, the MCP
     * exclusion and the transport flags. The prompt is not part of the argv — it is
     * fed on stdin (FR24, D18).
     *
     * @param role the round's role, which fixes its permission mode; never null
     * @param binary the CLI binary name or path; never null
     * @param invocationFlags the already-rendered {@code --model}/settings flags;
     *     never null, may be empty
     * @return the assembled argv; never null
     */
    static List<String> fromRenderedFlags(AgentRole role, String binary, List<String> invocationFlags) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add(PRINT_FLAG);
        command.addAll(invocationFlags);
        command.add(PERMISSION_MODE_FLAG);
        command.add(role.permissionMode());
        command.add(STRICT_MCP_CONFIG_FLAG);
        command.add(OUTPUT_FORMAT_FLAG);
        command.add(STREAM_JSON);
        command.add(VERBOSE_FLAG);
        return List.copyOf(command);
    }
}
