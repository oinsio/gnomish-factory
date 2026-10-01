package com.github.oinsio.gnomish.app.project;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The factory home: the one folder holding all operator state, named by {@code GNOMISH_HOME} and
 * by default {@code <user.home>/.gnomish} (FR1, design D1). The only code that knows the folder
 * names at the root — the host file {@code factory.yaml}, the host {@code secrets/}, the
 * project-less log file, and {@code projects/}, below which {@link ProjectLayout} knows the rest.
 * No other production code reads {@code user.home} or spells the home folder's name.
 *
 * <p>Built once per process from the Spring {@code Environment}, which exposes {@code
 * GNOMISH_HOME} as the OS variable or, in in-process specs, as a system property of the same name
 * — never from {@code System.getenv} directly (D1).
 *
 * <p>Pure path computation — no filesystem access, nothing created.
 *
 * <p>Implements FR1, FR8, FR11 of add-project-registry.
 */
public final class FactoryHome {

    /** The variable naming the factory home. */
    public static final String HOME_VARIABLE = "GNOMISH_HOME";

    private static final String USER_HOME = "user.home";

    private final Path root;

    private FactoryHome(Path root) {
        this.root = root;
    }

    /**
     * A property source the home is read from: in production the Spring {@code Environment}'s
     * {@code getProperty}, so the OS variable, the system property and the user's home folder all
     * come through the one lookup.
     */
    @FunctionalInterface
    public interface PropertyLookup {
        /** The value of {@code name}, or {@code null} when it is not set. */
        @Nullable
        String get(String name);
    }

    /**
     * Builds the home from {@code GNOMISH_HOME}, falling back to {@code <user.home>/.gnomish} when
     * the variable is unset or blank; a relative value resolves against the working directory.
     *
     * @param properties the property lookup, e.g. {@code environment::getProperty}
     * @return the factory home; its folder is not checked for existence
     * @throws IllegalStateException if neither {@code GNOMISH_HOME} nor {@code user.home} is set
     */
    public static FactoryHome from(PropertyLookup properties) {
        String home = properties.get(HOME_VARIABLE);
        if (home != null && !home.isBlank()) {
            return at(Path.of(home));
        }
        String userHome = properties.get(USER_HOME);
        if (userHome == null) {
            throw new IllegalStateException(
                    HOME_VARIABLE + " is not set and " + USER_HOME + " is unknown: set " + HOME_VARIABLE);
        }
        return at(Path.of(userHome, ".gnomish"));
    }

    /**
     * The home at an explicit folder — for fixtures that own a temporary home.
     *
     * @param root the home folder; made absolute and normalized
     * @return the factory home
     */
    public static FactoryHome at(Path root) {
        return new FactoryHome(root.toAbsolutePath().normalize());
    }

    /** The home folder itself. */
    public Path root() {
        return root;
    }

    /** The host configuration file, {@code factory.yaml} (FR5). */
    public Path hostConfig() {
        return root.resolve("factory.yaml");
    }

    /**
     * The file of secret {@code name} in the host secrets folder, consulted after the project's (FR8).
     *
     * @param name the secret's variable name; one path segment
     * @throws IllegalArgumentException if {@code name} is not one path segment
     */
    public Path hostSecret(String name) {
        return root.resolve("secrets").resolve(PathSegment.require(name, "secret name"));
    }

    /** The log file of a command that runs with no project, {@code logs/factory.log} (FR11). */
    public Path hostLogFile() {
        return root.resolve("logs").resolve("factory.log");
    }

    /** The folder holding one folder per registered project; the registry scans it (FR2, FR3). */
    public Path projects() {
        return root.resolve("projects");
    }

    /**
     * The layout of one registered project, {@code projects/<name>}.
     *
     * @param name the project
     */
    public ProjectLayout project(ProjectName name) {
        return new ProjectLayout(name, projects().resolve(name.value()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FactoryHome that && root.equals(that.root);
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
