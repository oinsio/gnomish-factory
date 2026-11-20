package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.executorcontract.Policy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of the argv fragment a request's abstract {@link Policy} compiles to (design D5 of
 * extract-agent-round-runner): the tool sets, the permission mode and the MCP exclusion. Every
 * Claude command line takes its fragment from here, so no argv exists without a mode, and no
 * policy maps to the mode that skips every permission check — the compiler cannot spell it.
 *
 * <ul>
 *   <li>{@code readOnly} → {@code --permission-mode dontAsk} with the read-only tool set; a
 *       settings {@code allowedTools} list may only narrow it (the intersection, in the settings'
 *       order), never widen it — a write-capable tool it names is dropped. An empty intersection
 *       omits {@code --allowedTools}: under {@code dontAsk} nothing outside it runs.
 *   <li>{@code writeExactly(path)} → {@code --permission-mode acceptEdits} with the settings'
 *       {@code allowedTools} and a pinpoint {@code Write(path)} allowance appended, never omitted
 *       — the answer-path allowance, the legacy decision-file compatibility of FR5.
 *   <li>{@code workspaceWrite} → {@code --permission-mode acceptEdits} with the settings' tool
 *       lists passed through.
 * </ul>
 *
 * <p>Every policy carries {@code --strict-mcp-config} with no {@code --mcp-config}: no MCP server —
 * the operator's or a {@code .mcp.json} in the working copy — starts or adds tool definitions
 * (design D2 of fix-operator-blockers). {@code disallowedTools} passes through for every policy: it
 * only ever narrows.
 *
 * <p>Implements FR3, FR5 of extract-agent-round-runner (design D5).
 */
final class ClaudePolicyCompiler {

    /** The CLI's mode for a run that may edit its working copy. */
    static final String ACCEPT_EDITS = "acceptEdits";

    /** The CLI's mode for a run that may use only its pre-approved tools. */
    static final String DONT_ASK = "dontAsk";

    /** The read-only tool set of a {@code readOnly} run: the most its settings can leave. */
    static final List<String> READ_ONLY_TOOLS = List.of("Read", "Grep", "Glob");

    private static final String ALLOWED_TOOLS_FLAG = "--allowedTools";

    private static final String DISALLOWED_TOOLS_FLAG = "--disallowedTools";

    private static final String PERMISSION_MODE_FLAG = "--permission-mode";

    private static final String STRICT_MCP_CONFIG_FLAG = "--strict-mcp-config";

    private static final String ALLOWED_TOOLS_KEY = ClaudeSettings.ALLOWED_TOOLS;

    private static final String DISALLOWED_TOOLS_KEY = ClaudeSettings.DISALLOWED_TOOLS;

    private ClaudePolicyCompiler() {}

    /**
     * Compiles {@code policy} with the request's settings into its argv fragment.
     *
     * @param policy the request's abstract policy; never null
     * @param settings the opaque, already-validated settings map; may be empty, never null
     * @return the fragment, in the order {@code --allowedTools}, {@code --disallowedTools}, {@code
     *     --permission-mode}, {@code --strict-mcp-config}
     */
    static List<String> compile(Policy policy, Map<String, Object> settings) {
        List<String> argv = new ArrayList<>();
        @Nullable Object allowed = settings.get(ALLOWED_TOOLS_KEY);
        String mode =
                switch (policy) {
                    case Policy.ReadOnly ignored -> {
                        addToolList(argv, ALLOWED_TOOLS_FLAG, narrowedToReadOnly(allowed));
                        yield DONT_ASK;
                    }
                    case Policy.WriteExactly exactly -> {
                        List<String> tools = toolNames(allowed);
                        tools.add("Write(" + exactly.path() + ")");
                        addToolList(argv, ALLOWED_TOOLS_FLAG, tools);
                        yield ACCEPT_EDITS;
                    }
                    case Policy.WorkspaceWrite ignored -> {
                        addToolList(argv, ALLOWED_TOOLS_FLAG, toolNames(allowed));
                        yield ACCEPT_EDITS;
                    }
                };
        addToolList(argv, DISALLOWED_TOOLS_FLAG, toolNames(settings.get(DISALLOWED_TOOLS_KEY)));
        argv.add(PERMISSION_MODE_FLAG);
        argv.add(mode);
        argv.add(STRICT_MCP_CONFIG_FLAG);
        return List.copyOf(argv);
    }

    private static List<String> narrowedToReadOnly(@Nullable Object rawAllowedTools) {
        if (!(rawAllowedTools instanceof List<?>)) {
            return READ_ONLY_TOOLS;
        }
        Set<String> narrowed = new LinkedHashSet<>(toolNames(rawAllowedTools));
        narrowed.retainAll(READ_ONLY_TOOLS);
        return List.copyOf(narrowed);
    }

    private static List<String> toolNames(@Nullable Object rawTools) {
        List<String> names = new ArrayList<>();
        if (rawTools instanceof List<?> tools) {
            for (Object tool : tools) {
                names.add(String.valueOf(tool));
            }
        }
        return names;
    }

    private static void addToolList(List<String> argv, String flag, List<String> tools) {
        if (tools.isEmpty()) {
            return;
        }
        argv.add(flag);
        argv.add(String.join(",", tools));
    }
}
