package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One process to run through {@link TaskExecutionEnvironment#exec(ExecCommand)}
 * (design D1): the argv, the factory-set environment fragment, optional stdin
 * content, and whether stderr is merged into stdout.
 *
 * <p>The {@code env} fragment is the factory-set protocol layer of the child
 * environment (D6, FR9): the adapter composes it with its base set and the
 * operator passthrough via {@code ChildEnvAllowlist} — nothing else is
 * inherited. Prompts and other large or sensitive inputs travel in {@code
 * stdin}, never in argv (FR24, D18).
 *
 * <p>Implements FR1, FR9, FR24 of add-sandbox-core.
 *
 * @param command the argv, binary first; never null, never empty; inert data,
 *     never assembled from environment-originated content
 * @param env factory-set environment variables for the child process, by exact
 *     name; never null, may be empty; defensively copied
 * @param stdin content fed to the process's standard input, or {@code null} to
 *     leave stdin empty
 * @param mergeStderr whether the child's stderr is merged into stdout (a
 *     command check reads one chronological stream; an agent round keeps them
 *     separate and parses stdout alone)
 */
public record ExecCommand(
        List<String> command,
        Map<String, String> env,
        @Nullable String stdin,
        boolean mergeStderr) {

    public ExecCommand {
        command = List.copyOf(command);
        env = Map.copyOf(env);
        requireNonEmpty(command);
    }

    /**
     * A command with no extra environment, no stdin, and separate stderr — the
     * plainest form.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion
     * JVM on this method's NULL_RETURNS mutant — deterministic RUN_ERROR with
     * zero tests observed, not a real test gap — the same JDK 17+ JVMTI
     * RedefineClasses restriction on record classes as the annotated methods of
     * Decision/Finding/PipelineDefinition (hcoles/pitest#1285, not fixable via
     * PIT config). The method stays behaviorally covered: adapter specs across
     * the environment and agent packages exec through {@code ExecCommand.of}
     * and dereference its return value, and this record's sibling {@code
     * requireNonEmpty} mutants are killed normally.
     *
     * @param command the argv, binary first; never null, never empty
     * @return the exec command; never null
     */
    @DoNotMutate
    public static ExecCommand of(List<String> command) {
        return new ExecCommand(command, Map.of(), null, false);
    }

    /**
     * Fails fast on an empty {@code command}: a process needs at least a binary.
     * Kept as an explicit static method rather than inline in the compact
     * constructor because PIT's record filter suppresses mutations inside a
     * record's canonical constructor.
     */
    private static void requireNonEmpty(List<String> command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("ExecCommand.command must not be empty");
        }
    }
}
