package com.github.oinsio.gnomish.app.project;

import java.nio.file.Path;

/**
 * The registered clone a project-scoped command works in: the project it belongs to, its name
 * within that project, its path and the project's layout under the factory home (FR3, design D2).
 * Built only by the project registry, from an exact match of the operator's {@code --dir} against
 * a registered path, so no consumer computes a project name or a worktree folder of its own — the
 * value every project-scoped consumer takes instead of a clone directory paired with a worktree root.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3, FR9, NFR-R2 of add-project-registry.
 *
 * @param project the project the clone is registered to
 * @param cloneName the clone's name within the project
 * @param clonePath the clone's registered path; absolute and normalized
 * @param layout the project's layout; its name is {@code project}
 */
public record RegisteredClone(ProjectName project, CloneName cloneName, Path clonePath, ProjectLayout layout) {

    public RegisteredClone {
        requireConsistent(project, clonePath, layout);
    }

    /** The clone's own worktree folder, {@code projects/<name>/worktrees/<clone>} (FR9, NFR-R2). */
    public Path worktrees() {
        return layout.worktrees(cloneName);
    }

    /**
     * Refuses a relative clone path or a layout of another project. A static method rather than
     * inline in the compact constructor: PIT's record filter suppresses every mutation inside a
     * record's canonical constructor, which would exempt these checks from the mutation gate.
     */
    private static void requireConsistent(ProjectName project, Path clonePath, ProjectLayout layout) {
        if (!clonePath.isAbsolute()) {
            throw new IllegalArgumentException("clone path must be absolute: " + clonePath);
        }
        if (!layout.name().equals(project)) {
            throw new IllegalArgumentException(
                    "layout of project '" + layout.name() + "' given for project '" + project + "'");
        }
    }
}
