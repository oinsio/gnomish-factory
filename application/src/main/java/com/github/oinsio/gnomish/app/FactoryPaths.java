package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The two installation directories the factory keeps outside every project clone: where task
 * worktrees are materialized and the home directory the serve observability files live under.
 * One value with two named accessors, so no call site can transpose the two paths and no
 * injection point is resolved by parameter name (design D4 of collapse-composition-roots).
 *
 * <p>Relays take the value whole; a leaf that needs one path is handed that one path by its relay,
 * except the two Spring-fed commands, which have no relay to read it for them (design,
 * {@code FactoryPaths} row of the single-owner table).
 *
 * <p>Implements FR3, FR5 of collapse-composition-roots.
 *
 * @param worktreesRoot the root under which per-task git worktrees are materialized (FR6 of
 *     add-git-workflow, design D6)
 * @param homeDir the home directory {@code ~/.gnomish/serve/} resolves against (FR9, design D2 of
 *     add-serve-observability); tests substitute a temp directory
 */
public record FactoryPaths(Path worktreesRoot, Path homeDir) {

    /**
     * The installation layout under one home directory: worktrees in {@code
     * <home>/.gnomish/worktrees}, outside any project clone, so one factory instance can serve
     * several projects without littering any of them.
     *
     * @param home the user's home directory
     * @return the paths production wiring uses; never null
     */
    public static FactoryPaths underHome(Path home) {
        return new FactoryPaths(home.resolve(".gnomish").resolve("worktrees"), home);
    }
}
