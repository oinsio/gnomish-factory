package com.github.oinsio.gnomish.app

import java.nio.file.Path

/**
 * {@link FactoryPaths} for the specs of a command that reads only one of the two paths. The other
 * one is a directory that never exists, distinct from the path under test, so a command reading
 * the wrong accessor fails its spec instead of passing on a shared value.
 *
 * <p>Implements FR3 of collapse-composition-roots.
 */
final class FactoryPathsFixture {

    private FactoryPathsFixture() {}

    /** Paths whose worktree root is {@code worktreesRoot}; the home directory is never there. */
    static FactoryPaths worktreesAt(Path worktreesRoot) {
        new FactoryPaths(worktreesRoot, worktreesRoot.resolve('unused-home-dir'))
    }

    /** Paths whose home directory is {@code homeDir}; the worktree root is never there. */
    static FactoryPaths homeAt(Path homeDir) {
        new FactoryPaths(homeDir.resolve('unused-worktrees-root'), homeDir)
    }
}
