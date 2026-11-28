package com.github.oinsio.gnomish.domain.branch;

/**
 * One path under the {@code .gnomish-task/} state directory, repository-relative with no leading
 * separator — the shape every medium takes: a working-copy-relative file read in a box, a tree
 * lookup at a commit, a pathspec of an admission rule. Only {@link EnvelopePaths} builds one, so a
 * value of this type is a path the owner spelled, never one a caller joined by hand.
 *
 * <p>Implements FR9 of extract-agent-round-runner (design D3, D8: the answer file's one owner).
 *
 * @param relative the path, beneath {@link EnvelopePaths#DIR}; never escaping it
 */
public record EnvelopePath(String relative) {

    /** Refuses a path outside the state directory or one that climbs out of it. */
    public EnvelopePath {
        if (!relative.startsWith(EnvelopePaths.DIR) || relative.contains("..")) {
            throw new IllegalArgumentException("an envelope path lies beneath " + EnvelopePaths.DIR + ": " + relative);
        }
    }
}
