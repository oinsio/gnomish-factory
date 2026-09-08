package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.gitobjects.GitObjects;
import java.nio.file.Path;

/**
 * Which tree one invocation's pipeline law is bound to (design D12 of add-base-ref-resolution):
 * the working tree of a repository, or the git objects of one commit — the <em>law commit</em>.
 * A run assembles from exactly one binding, chosen by fact rather than by mode: any path that
 * resolved a ref binds {@link AtRevision} (take, serve, container, resume, manual {@code run
 * --base}), while the git-less in-place mode and manual {@code run} without {@code --base} bind
 * {@link WorkingTree}, where an uncommitted edit is meant to be law.
 *
 * <p><b>The one owner of the law-root rule.</b> The law root is the {@code .gnomish} directory —
 * of the working tree, or of the law commit's own tree — which is also the root the loader
 * validates file references against, so a reference that validates at load reads at run in every
 * medium. That rule is spelled here and nowhere else: no call site resolves {@code .gnomish}, and
 * a binding hands its realization a root that is already right for its medium.
 *
 * <p><b>The one owner of "law belongs to this repository".</b> A binding knows the repository it
 * reads from: the git objects a revision peels in, the working tree a {@code .gnomish/} directory
 * sits under, and the repository the external-check pin guard reads its repository-relative pin
 * paths from. Carrying the root inside the binding — rather than beside it as a second {@code
 * Path} — is what makes the binding a value with an invariant instead of a bag: a law commit and
 * a pin path can no longer be read from two different repositories by a transposed argument. In
 * the in-place mode the "repository" root may not be a repository at all; the pin guard then
 * degrades fail-closed, which is the binding's caller's documented contract, not a defect here.
 *
 * <p><b>A revision, not yet a commit.</b> A binding is a value the use-case layer states; peeling
 * a revision to its commit needs git, which lives an adapter down. The revision is therefore
 * resolved exactly once, where the law source is opened, and the same commit is handed to the law
 * reader and to the pin guard — so law and pin come from one SHA by construction, which the
 * pre-D12 {@code "HEAD"} pin could not promise.
 *
 * <p>Implements FR11, FR12, M5 of add-base-ref-resolution.
 */
public sealed interface LawBinding {

    /** The law root's directory name, in a working tree and in a git tree alike. */
    String LAW_ROOT = ".gnomish";

    /**
     * The repository this binding's law belongs to: the root the working tree or the bare objects
     * are opened under, and the root the external-check pin guard's pin paths are relative to.
     *
     * @return the repository root; never null
     */
    Path repositoryRoot();

    /**
     * Binds law to the working tree under {@code repositoryRoot} — the in-place mode's {@code
     * --dir} workspace, or the factory clone in a manual {@code run} without {@code --base}.
     *
     * @param repositoryRoot the project root whose {@code .gnomish/} directory holds the law
     * @return the working-tree binding; never null
     */
    static LawBinding workingTree(Path repositoryRoot) {
        return new WorkingTree(repositoryRoot);
    }

    /**
     * Binds law to the {@code .gnomish/} tree of whatever commit {@code revision} peels to in the
     * repository at {@code repositoryRoot}, read straight out of git objects with no checkout: the
     * factory clone's working tree, index and {@code HEAD} then play no part in the law.
     *
     * @param repositoryRoot the factory clone the revision is resolved in
     * @param revision any revision that clone resolves — a base branch, a tag, or the pinned SHA
     *     of a task
     * @return the git-objects binding; never null
     */
    static LawBinding atRevision(Path repositoryRoot, String revision) {
        return new AtRevision(repositoryRoot, revision);
    }

    /**
     * Binds law to the commit the factory clone at {@code repositoryRoot} is checked out at — the
     * commit its task branches are cut from until base resolution (FR4) supplies each task its
     * own. This is where "this path resolved no base of its own yet" is spelled, once, instead of
     * a {@code "HEAD"} literal scattered across the take, serve, container and resume paths.
     *
     * @param repositoryRoot the factory clone whose checkout is the law
     * @return the git-objects binding at the clone's checkout; never null
     */
    static LawBinding atCheckout(Path repositoryRoot) {
        return atRevision(repositoryRoot, GitObjects.HEAD);
    }

    /**
     * Law from a working tree.
     *
     * @param repositoryRoot the project root the {@link #lawRoot} sits under
     */
    record WorkingTree(Path repositoryRoot) implements LawBinding {

        /**
         * The {@code .gnomish/} directory of the working tree — resolved here, by the one owner of
         * the rule, so no realization or call site spells it.
         *
         * @return the law root directory; never null
         */
        public Path lawRoot() {
            return repositoryRoot.resolve(LAW_ROOT);
        }
    }

    /**
     * Law from git objects at one revision.
     *
     * @param repositoryRoot the repository the revision is resolved in
     * @param revision the revision the law commit is read from
     */
    record AtRevision(Path repositoryRoot, String revision) implements LawBinding {}
}
