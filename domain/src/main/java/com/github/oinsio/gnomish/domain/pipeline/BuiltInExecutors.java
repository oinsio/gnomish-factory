package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The six built-in executor names (design D18 of migrate-verify-to-executor-contract, "The six
 * built-in names have one owner"): the five verdict built-ins and the {@code command} stage
 * built-in, compiled into the factory and registered by it, never declared by a law. This is the
 * one spelling of the set: the law's load validation refuses a declaration under any of the six
 * and lists them beside the law's own declarations when a check names an unknown executor, and the
 * executor registry's refusal reads the same set as a safety net behind that load error. Each
 * built-in's own {@code NAME} constant refers to its constant here.
 *
 * <p>Implements FR1, FR13 of migrate-verify-to-executor-contract.
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

    /** The six names, in the order an operator reads them. */
    public static final List<String> NAMES = List.of(FILES_EXIST, SHELL, GITHUB_ACTIONS, SCM_STATUS, HTTP, COMMAND);

    private BuiltInExecutors() {}

    /**
     * Whether {@code name} is one of the six built-in names.
     *
     * @param name an executor name; never null
     * @return {@code true} for a built-in's name
     */
    public static boolean isBuiltIn(String name) {
        return NAMES.contains(name);
    }
}
