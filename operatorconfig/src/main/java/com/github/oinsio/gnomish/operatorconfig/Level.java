package com.github.oinsio.gnomish.operatorconfig;

/**
 * Where a {@code factory.*} key may be set (FR6 of add-project-registry, design D4). The operator
 * configuration has three places a person writes a value — the host file {@code
 * GNOMISH_HOME/factory.yaml}, the resolved project's {@code projects/<name>/project.yaml}, and the
 * command line — and a key's level names the subset it is accepted from. A {@code FACTORY_*}
 * environment variable is accepted at no level: it is refused whatever key it names (FR7).
 *
 * <p>Implements FR6, NFR-S1 of add-project-registry.
 */
public enum Level {
    /** Describes the installation: the host file or the command line, never a project file. */
    HOST,
    /** Describes one project: the project file or the command line, never the host file. */
    PROJECT,
    /** Meaningful at either scope: the host file, the project file or the command line. */
    ANY,
    /**
     * Widens or selects the sandbox a project's tasks run in: the project's own file and nowhere
     * else — not the host file, not the command line (NFR-S1). An operator reads a project's whole
     * sandbox boundary in one file (G2).
     */
    SANDBOX_BOUNDARY,
}
