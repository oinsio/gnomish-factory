package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The command line a dialect rendered for one request, binary first, and what the command reads on
 * its standard input (FR2, FR3 of extract-agent-round-runner). The runner launches it as given and
 * never edits it: what a vendor's CLI is told — the compiled policy, the model, the bounds, the answer
 * schema, the resume, the prompt on stdin or in an argument — is the dialect's alone. The runner hands
 * {@link #stdin} to the launch owner, which gives it to the child as a file, never a pipe a factory
 * thread writes into.
 *
 * <p>Implements FR2, FR3 of extract-agent-round-runner.
 *
 * @param argv the binary and its arguments; never empty, copied and unmodifiable
 * @param stdin what the command reads on its standard input, or {@code null} for none
 */
public record RenderedArgv(List<String> argv, @Nullable String stdin) {

    public RenderedArgv {
        argv = nonEmpty(argv);
    }

    /**
     * A command line that reads nothing on its standard input.
     *
     * @param argv the binary and its arguments; never empty
     */
    public RenderedArgv(List<String> argv) {
        this(argv, null);
    }

    /**
     * Copies {@code argv}, refusing an empty one: a command line names at least its binary. Kept as
     * an explicit static method rather than inline in the compact constructor: PIT's record filter
     * suppresses all mutations inside a record's canonical constructor, which would silently exempt
     * this validation from the 100% mutation gate.
     *
     * <p>{@code @DoNotMutate}, JVMTI redefinition limit ({@code testing.md}): since the record gained
     * its {@code @Nullable} {@code stdin} component, PIT's mutant of this check ends its minion with
     * "class redefinition failed: attempted to change the class NestHost, NestMembers, Record, or
     * PermittedSubclasses attribute" (RUN_ERROR, hcoles/pitest#1285, measured 2026-11-20 on JDK
     * 25.0.3), so no test can observe it. {@code AgentDialectValuesSpec} ("an argv with no binary is
     * refused") covers the refusal.
     */
    @DoNotMutate
    private static List<String> nonEmpty(List<String> argv) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("RenderedArgv.argv must name at least the binary");
        }
        return List.copyOf(argv);
    }
}
