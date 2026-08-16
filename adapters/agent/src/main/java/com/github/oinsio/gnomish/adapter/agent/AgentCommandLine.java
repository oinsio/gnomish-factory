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
 * <p>Implements FR1, FR3, NFR-S1 of fix-oversized-adapters; FR24 of add-sandbox-core.
 */
final class AgentCommandLine {

    private static final String PRINT_FLAG = "-p";

    private static final String OUTPUT_FORMAT_FLAG = "--output-format";

    private static final String STREAM_JSON = "stream-json";

    private static final String VERBOSE_FLAG = "--verbose";

    private AgentCommandLine() {}

    /**
     * Command with the invocation flags already rendered by the caller, inserted
     * verbatim after {@code -p} and before the transport flags. The prompt is not
     * part of the argv — it is fed on stdin (FR24, D18).
     *
     * @param binary the CLI binary name or path; never null
     * @param invocationFlags the already-rendered {@code --model}/settings flags;
     *     never null, may be empty
     * @return the assembled argv; never null
     */
    static List<String> fromRenderedFlags(String binary, List<String> invocationFlags) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add(PRINT_FLAG);
        command.addAll(invocationFlags);
        command.add(OUTPUT_FORMAT_FLAG);
        command.add(STREAM_JSON);
        command.add(VERBOSE_FLAG);
        return List.copyOf(command);
    }
}
