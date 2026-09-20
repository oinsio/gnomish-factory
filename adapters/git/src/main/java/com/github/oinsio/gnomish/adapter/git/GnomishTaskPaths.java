package com.github.oinsio.gnomish.adapter.git;

/**
 * The one spelling of the {@code .gnomish-task/} state directory and the paths beneath it, for
 * every branch reader and writer in this module: the name exists here once instead of being
 * retyped per class, so no caller addresses the directory by a name the others do not share.
 *
 * <p>Enforcement is a review obligation, not a build gate (rules {@code implementation.md}, item 4).
 * The type is package-private, so it constrains this module and nothing else; a new class here
 * retyping the literal compiles. The enforcement that would close it is a source scan in {@code
 * :bootstrap} — the shape {@code BaseHeadDefaultBoundarySpec} already uses — failing the build on
 * {@code ".gnomish-task"}, {@code "task.json"} or {@code "state.json"} in any production source
 * outside an allowlist. One out-of-module survivor is known and would be its first allowlist entry:
 * {@code ContainerTipReader} in {@code :bootstrap} spells both envelope paths itself, because a
 * package-private constant is out of its reach. Everything else the grep finds is an error-message
 * label or prose, not a path.
 *
 * <p>The domain layer names the two envelopes independently, in {@code
 * BranchShapeClassifier.TASK_FILE} / {@code STATE_FILE}: deliberate layer decoupling, since {@code
 * :domain} takes no dependency on this module. Those are diagnosis labels rather than paths — they
 * carry no directory — but the two spellings must stay equal, so a rename here renames them too.
 *
 * <p>Two shapes, because two consumers need different ones and mixing them is a real defect:
 * {@link #DIR_NAME} carries no separator and is what {@code Path.resolve} and a git pathspec take;
 * {@link #DIR} carries the trailing slash and is what a repository-relative prefix needs, so
 * {@code git diff -- .gnomish-task/} and {@code <ref>:.gnomish-task/task.json} select the
 * directory's contents rather than a path merely starting with its name.
 *
 * <p>Implements FR5 of harden-task-branch-contract — the spelling {@link FactoryOwnedPaths} derives
 * its ownership list from.
 */
final class GnomishTaskPaths {

    /** The state directory at the working-copy root, without a trailing separator. */
    static final String DIR_NAME = ".gnomish-task";

    /** The same directory as a repository-relative prefix, with its trailing separator. */
    static final String DIR = DIR_NAME + "/";

    /**
     * The task envelope, as a repository-relative path with no leading separator — the one shape
     * that serves both consumers: {@code Path.resolve} against a worktree root, and a tree lookup
     * such as {@code <ref>:.gnomish-task/task.json}.
     */
    static final String TASK_JSON_PATH = DIR + "task.json";

    /** The state envelope, in the same repository-relative shape as {@link #TASK_JSON_PATH}. */
    static final String STATE_JSON_PATH = DIR + "state.json";

    /**
     * The gnome-writable subtree of the decision-file protocol, without a trailing separator — the
     * shape a git pathspec exclusion takes.
     */
    static final String DECISIONS_DIR = DIR + "decisions";

    private GnomishTaskPaths() {}
}
