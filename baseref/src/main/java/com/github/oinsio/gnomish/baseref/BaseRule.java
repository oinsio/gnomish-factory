package com.github.oinsio.gnomish.baseref;

import java.util.Locale;

/**
 * Which tier of the priority order produced a base — the "why did this task branch from there"
 * half of the decision, pinned beside the ref and the SHA so the answer survives the run.
 *
 * <p>The constants are declared in priority order, highest first. That order is the specification:
 * a tier is consulted only when every tier above it abstained.
 *
 * <p>A future type-derived tier ({@code add-pipeline-routing}) belongs between {@link #DESIGNATOR}
 * and {@link #CONFIGURED_DEFAULT} — a base implied by the task's type is a weaker statement than one
 * a triager wrote on the task, and a stronger one than a project-wide default. It is named here and
 * does not exist.
 *
 * <p>Implements FR4, FR7, FR8 of add-base-ref-resolution.
 */
public enum BaseRule {

    /**
     * An operator passed {@code --base}. Not held against the allowed bases: they bound what a task's
     * author may choose, and the person at the terminal is not that author.
     */
    EXPLICIT_ARGUMENT,

    /** The task named a base in tracker metadata and the allowed bases accepted it. */
    DESIGNATOR,

    /** The project's configured {@code task-branch.base.default}. */
    CONFIGURED_DEFAULT,

    /** The repository default branch as the remote reported it — the zero-configuration answer. */
    REPOSITORY_DEFAULT_BRANCH,

    /**
     * The clone's local HEAD. Reachable from a manual {@code gnomish run} without {@code --base} and
     * from nowhere else, which is what keeps the pipeline author's offline loop working unchanged.
     */
    LOCAL_HEAD,

    /**
     * A pin written by a newer build under a rule this build does not recognize — forward-compat
     * only. Resolution never produces this value; it exists solely as the fold target for an
     * unrecognized wire token in {@link #fromWire(String)}, the same shape {@code RecoveryCause}
     * uses for its own unknown-token arm.
     */
    UNKNOWN;

    /** The wire token this rule is pinned into {@code task.json} as (FR7 of add-base-ref-resolution). */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a wire token back into its rule, folding an unrecognized token to {@link #UNKNOWN}
     * rather than failing — a pin written by a newer build under a rule this build does not
     * recognize stays readable (forward compatibility).
     */
    public static BaseRule fromWire(String wire) {
        for (BaseRule rule : values()) {
            if (rule.wireValue().equals(wire)) {
                return rule;
            }
        }
        return UNKNOWN;
    }
}
