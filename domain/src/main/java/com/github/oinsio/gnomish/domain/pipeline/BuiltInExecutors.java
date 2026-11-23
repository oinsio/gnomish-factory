package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The seven built-in executor names (design D18 of migrate-verify-to-executor-contract, "The six
 * built-in names have one owner"; design D12 of extract-agent-round-runner): the five verdict
 * built-ins, the {@code command} stage built-in, and {@code claude}, the agent entry an {@code
 * agent-cli} block resolves to — compiled into the factory and registered by it, never declared by a
 * law. This is the one spelling of the set: the law's load validation refuses a declaration under
 * any of the seven, and the executor registry's refusal reads the same set as a safety net behind
 * that load error. A check names a verdict built-in or the {@code command} built-in by name, never
 * {@link #CLAUDE}: an agent reaches a check only through a {@code judge} check's executor block
 * ({@link #CHECK_NAMES}). Each built-in's own {@code NAME} constant refers to its constant here.
 *
 * <p>Implements FR1, FR13 of migrate-verify-to-executor-contract; FR14 of extract-agent-round-runner.
 */
public final class BuiltInExecutors {

    /** The built-in a {@code builtin} check maps to: the existence of declared paths. */
    public static final String FILES_EXIST = "files-exist";

    /** The built-in a {@code command} check maps to: a command line judged by its exit and findings. */
    public static final String SHELL = "shell";

    /** The built-in an {@code external} check without a {@code provider} maps to. */
    public static final String GITHUB_ACTIONS = "github-actions";

    /** The built-in reader of named verdicts on the attempt SHA; it needs {@code expected}. */
    public static final String SCM_STATUS = "scm-status";

    /** The built-in status-code probe a {@code provider: http} check maps to. */
    public static final String HTTP = "http";

    /** The built-in a {@code command} stage runs: its declared command line. */
    public static final String COMMAND = "command";

    /**
     * The agent entry an {@code agent-cli} executor block resolves to (design D12 of
     * extract-agent-round-runner): the Claude dialect's stage and check halves.
     */
    public static final String CLAUDE = "claude";

    /** The seven names, in the order an operator reads them. */
    public static final List<String> NAMES =
            List.of(FILES_EXIST, SHELL, GITHUB_ACTIONS, SCM_STATUS, HTTP, COMMAND, CLAUDE);

    /**
     * The built-in names a check may name by its {@code executor} or {@code provider}: every name but
     * {@link #CLAUDE}, which a check reaches only through a {@code judge} check's executor block
     * (design D12 of extract-agent-round-runner rejects a name-only reference).
     */
    public static final List<String> CHECK_NAMES =
            NAMES.stream().filter(name -> !name.equals(CLAUDE)).toList();

    private BuiltInExecutors() {}

    /**
     * Whether {@code name} is one of the seven built-in names.
     *
     * @param name an executor name; never null
     * @return {@code true} for a built-in's name
     */
    public static boolean isBuiltIn(String name) {
        return NAMES.contains(name);
    }
}
