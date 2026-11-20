package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.executorcontract.Bounds;
import com.github.oinsio.gnomish.executorcontract.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles the argv for one {@code claude -p} round: binary, {@code -p} (print mode, with the
 * prompt delivered on stdin, never as an argument — FR24, D18 of add-sandbox-core), the model and
 * limit hints ({@link AgentInvocationOptions}), the fragment the request's policy compiles to
 * ({@link ClaudePolicyCompiler}: tool sets, permission mode, MCP exclusion), and the hard-wired
 * print-mode transport flags that are protocol internals, not settings (FR12) — {@code
 * --output-format stream-json --verbose}. The prompt itself travels through {@link
 * com.github.oinsio.gnomish.sandbox.ExecCommand#stdin()} so a large accumulated-feedback prompt
 * cannot hit the platform's single-argument size limit and is not exposed in process listings.
 *
 * <p>The policy is a required parameter: no argv exists without the mode it compiles to.
 *
 * <p>Implements FR1, FR3, NFR-S1 of fix-oversized-adapters; FR24 of add-sandbox-core; FR1–FR4,
 * NFR-S1, NFR-S2 of fix-operator-blockers; FR3, FR5, NFR-C1 of extract-agent-round-runner.
 */
public final class AgentCommandLine {

    private static final String PRINT_FLAG = "-p";

    private static final String OUTPUT_FORMAT_FLAG = "--output-format";

    private static final String STREAM_JSON = "stream-json";

    private static final String VERBOSE_FLAG = "--verbose";

    private AgentCommandLine() {}

    /**
     * The command for one round. The prompt is not part of the argv — it is fed on stdin.
     *
     * @param binary the CLI binary name or path; never null
     * @param model the pinned model id
     * @param settings the opaque, already-validated settings map; may be empty, never null
     * @param policy the round's abstract policy, which fixes its mode and tool sets; never null
     * @param bounds the round's limit hints; {@link Bounds#none()} where the caller has none
     * @return the assembled argv; never null
     */
    public static List<String> of(
            String binary, String model, Map<String, Object> settings, Policy policy, Bounds bounds) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add(PRINT_FLAG);
        command.addAll(AgentInvocationOptions.render(model, settings, bounds));
        command.addAll(ClaudePolicyCompiler.compile(policy, settings));
        command.add(OUTPUT_FORMAT_FLAG);
        command.add(STREAM_JSON);
        command.add(VERBOSE_FLAG);
        return List.copyOf(command);
    }
}
