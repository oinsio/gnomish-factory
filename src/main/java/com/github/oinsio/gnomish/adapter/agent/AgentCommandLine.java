package com.github.oinsio.gnomish.adapter.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles the argv for one {@code claude -p} round: binary, {@code -p
 * <prompt>}, an invocation-flags segment that varies by builder, and the
 * hard-wired print-mode transport flags that are protocol internals, not
 * settings (FR12) — {@code --output-format stream-json --verbose}. Extracted
 * from {@link AgentProcessLauncher} (design D2) to consolidate the
 * command-assembly duplication shared by its four {@code launch*} overloads;
 * the launcher itself keeps only the {@code ProcessBuilder} start seam
 * (NFR-S1).
 *
 * <p>Implements FR1, FR3, NFR-S1 of fix-oversized-adapters.
 */
final class AgentCommandLine {

    private static final String PRINT_FLAG = "-p";

    private static final String OUTPUT_FORMAT_FLAG = "--output-format";

    private static final String STREAM_JSON = "stream-json";

    private static final String VERBOSE_FLAG = "--verbose";

    private AgentCommandLine() {}

    /**
     * Transport-only command: binary + {@code -p <prompt>} + transport flags,
     * with no invocation options. Used by the no-model {@code launch}
     * overload.
     */
    static List<String> transportOnly(String binary, String prompt) {
        return List.of(binary, PRINT_FLAG, prompt, OUTPUT_FORMAT_FLAG, STREAM_JSON, VERBOSE_FLAG);
    }

    /**
     * Command with invocation options rendered by {@link
     * AgentInvocationOptions#render(String, Map)} from {@code model} and
     * {@code settings}, inserted after the prompt and before the transport
     * flags. Used by the model+settings {@code launch} overloads.
     */
    static List<String> fromModelAndSettings(String binary, String prompt, String model, Map<String, Object> settings) {
        return assemble(binary, prompt, AgentInvocationOptions.render(model, settings));
    }

    /**
     * Command with the invocation flags already rendered by the caller,
     * inserted verbatim after the prompt and before the transport flags. Used
     * by {@code launchWithFlags}, whose flags may bake in a decision-file
     * path that {@code (model, settings)} alone cannot produce.
     */
    static List<String> fromRenderedFlags(String binary, String prompt, List<String> invocationFlags) {
        return assemble(binary, prompt, invocationFlags);
    }

    private static List<String> assemble(String binary, String prompt, List<String> invocationFlags) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add(PRINT_FLAG);
        command.add(prompt);
        command.addAll(invocationFlags);
        command.add(OUTPUT_FORMAT_FLAG);
        command.add(STREAM_JSON);
        command.add(VERBOSE_FLAG);
        return List.copyOf(command);
    }
}
