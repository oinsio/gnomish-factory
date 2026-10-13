package com.github.oinsio.gnomish.gittransfer;

import java.util.Objects;

/**
 * The factory register of one task as a ref name: {@code refs/gnomish-factory/<task>}, naming the
 * factory's latest closing commit of that task — in the factory clone and in origin (design D13 of
 * tighten-spec-contract). The register moves only with a factory closing commit, and the push that
 * moves it moves the task branch with it in one atomic push ({@link GitPush#closing}); a mid-round
 * push moves the branch alone ({@link GitPush#branchOnly}).
 *
 * <p>This leaf is the one spelling of the namespace, so a reader and the writer cannot disagree on
 * where the register lives; which task core string fills the {@code <task>} slot is the caller's
 * (the git adapter derives it from the tracker's id the same way it derives the branch name). The
 * value refuses a name outside the namespace, an empty task, and a task that would nest a second
 * level or carry a refspec separator: the name is placed into refspecs as it stands.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 *
 * @param name the full ref name, {@code refs/gnomish-factory/<task>}
 */
public record RegisterRef(String name) {

    /** The namespace every register lives under, in the factory clone and in origin. */
    public static final String NAMESPACE = "refs/gnomish-factory/";

    /**
     * Where a touchpoint fetch that must not overwrite the clone's own register lands origin's: a
     * remote-tracking name, as {@code refs/remotes/origin/<branch>} is for the branch.
     */
    private static final String TRACKING_NAMESPACE = "refs/remotes/origin/gnomish-factory/";

    public RegisterRef {
        if (!name.startsWith(NAMESPACE)) {
            throw new IllegalArgumentException("a factory register lives under " + NAMESPACE + ": " + name);
        }
        String task = name.substring(NAMESPACE.length());
        if (task.isBlank() || task.contains("/") || task.contains(":") || task.startsWith("-")) {
            throw new IllegalArgumentException(
                    "a factory register names one task, with no '/', ':' or leading '-': " + name);
        }
    }

    /**
     * The register of {@code task}.
     *
     * @param task the task's ref-safe core string, the part of its branch name after the prefix
     * @return the register value
     */
    public static RegisterRef forTask(String task) {
        Objects.requireNonNull(task, "task");
        return new RegisterRef(NAMESPACE + task);
    }

    /**
     * The remote-tracking name a fetch of origin's register lands in when the clone's own register
     * must be kept, {@code refs/remotes/origin/gnomish-factory/<task>}.
     *
     * @return the tracking ref name
     */
    public String trackingName() {
        return TRACKING_NAMESPACE + name.substring(NAMESPACE.length());
    }
}
