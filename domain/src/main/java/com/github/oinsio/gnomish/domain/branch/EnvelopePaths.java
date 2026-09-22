package com.github.oinsio.gnomish.domain.branch;

/**
 * The one spelling of the {@code .gnomish-task/} state directory and the paths beneath it, for
 * every module: the branch readers and writers of the git adapter, the container tip reader in the
 * composition root, and the domain's own shape diagnoses all address the envelope by these
 * constants rather than by a literal each retypes.
 *
 * <p>The owner sits in {@code :domain} because that is the lowest module every consumer already
 * depends on (design D8 of fix-envelope-medium). The previous owner was package-private in {@code
 * adapters/git}, which put it out of reach of two consumers; they spelled the names again, and a
 * third spelling lived here as diagnosis labels. {@link BranchShapeClassifier#TASK_FILE} and {@link
 * BranchShapeClassifier#STATE_FILE} are now references to this class, kept public for their
 * existing readers.
 *
 * <p>Enforcement is mechanical rather than a review obligation: {@code EnvelopeMediumBoundarySpec}
 * in {@code :bootstrap} scans every production source tree, with comments stripped, and fails the
 * build on {@code ".gnomish-task"}, {@code "task.json"} or {@code "state.json"} spelled outside
 * this file.
 *
 * <p>Two shapes of the directory, because two consumers need different ones and mixing them is a
 * real defect: {@link #DIR_NAME} carries no separator and is what {@code Path.resolve} and a git
 * pathspec take; {@link #DIR} carries the trailing slash and is what a repository-relative prefix
 * needs, so {@code git diff -- .gnomish-task/} and {@code <ref>:.gnomish-task/task.json} select the
 * directory's contents rather than a path merely starting with its name.
 *
 * <p>Implements FR9 of fix-envelope-medium; carries forward FR5 of harden-task-branch-contract —
 * the spelling the git adapter's ownership list derives from.
 */
public final class EnvelopePaths {

    /** The state directory at the working-copy root, without a trailing separator. */
    public static final String DIR_NAME = ".gnomish-task";

    /** The same directory as a repository-relative prefix, with its trailing separator. */
    public static final String DIR = DIR_NAME + "/";

    /** The task envelope's file name, as a diagnosis names it and as a path tail. */
    public static final String TASK_FILE = "task.json";

    /** The state envelope's file name, in the same two roles as {@link #TASK_FILE}. */
    public static final String STATE_FILE = "state.json";

    /**
     * The task envelope, as a repository-relative path with no leading separator — the one shape
     * that serves both consumers: {@code Path.resolve} against a worktree root, and a tree lookup
     * such as {@code <ref>:.gnomish-task/task.json}.
     */
    public static final String TASK_JSON_PATH = DIR + TASK_FILE;

    /** The state envelope, in the same repository-relative shape as {@link #TASK_JSON_PATH}. */
    public static final String STATE_JSON_PATH = DIR + STATE_FILE;

    /**
     * The gnome-writable subtree of the decision-file protocol, without a trailing separator — the
     * shape a git pathspec exclusion takes.
     */
    public static final String DECISIONS_DIR = DIR + "decisions";

    private EnvelopePaths() {}
}
