package com.github.oinsio.gnomish.app.project;

import java.nio.file.Path;

/**
 * The folder of one registered project under the factory home, {@code projects/<name>/}, and the
 * operator paths inside it: the project file, the project's secrets, the log file and serve folder
 * of an instance, and the worktree folder of a clone (FR1, FR9, FR10, FR11). Obtained only from
 * {@link FactoryHome#project(ProjectName)}, so every project path shares one root and one name.
 *
 * <p>Pure path computation — no filesystem access, nothing created.
 *
 * <p>Implements FR1, FR9, FR10, FR11 of add-project-registry.
 */
public final class ProjectLayout {

    private final ProjectName name;
    private final Path dir;

    ProjectLayout(ProjectName name, Path dir) {
        this.name = name;
        this.dir = dir;
    }

    /** The project this layout belongs to. */
    public ProjectName name() {
        return name;
    }

    /** The project folder, {@code <home>/projects/<name>}. */
    public Path dir() {
        return dir;
    }

    /** The project file, {@code project.yaml}: the clones and the project's configuration. */
    public Path config() {
        return dir.resolve("project.yaml");
    }

    /** The project's secrets folder, consulted ahead of the host's (FR8). */
    public Path secrets() {
        return dir.resolve("secrets");
    }

    /**
     * The log file of a project-scoped command, {@code logs/<instance>.log} (FR11).
     *
     * @param instance the configured instance name; one path segment
     */
    public Path logFile(String instance) {
        return dir.resolve("logs").resolve(PathSegment.require(instance, "instance name") + ".log");
    }

    /**
     * The serve observability folder of an instance, {@code serve/<instance>} (FR10).
     *
     * @param instance the configured instance name; one path segment
     */
    public Path serveDir(String instance) {
        return dir.resolve("serve").resolve(PathSegment.require(instance, "instance name"));
    }

    /**
     * The worktree folder of one clone, {@code worktrees/<clone>}, holding one worktree per task
     * (FR9).
     *
     * @param clone the clone within this project
     */
    public Path worktrees(CloneName clone) {
        return dir.resolve("worktrees").resolve(clone.value());
    }

    /** Equal by folder: the folder's last segment is the name, so the name adds nothing. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectLayout that && dir.equals(that.dir);
    }

    @Override
    public int hashCode() {
        return dir.hashCode();
    }

    @Override
    public String toString() {
        return dir.toString();
    }
}
