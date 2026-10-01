package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.operatorconfig.Level;

/**
 * The places an operator can set a {@code factory.*} key, and which levels each admits (FR6,
 * NFR-S1): the host file admits host and any keys; a project's own file admits project, any and
 * sandbox-boundary keys; the command line (JVM system properties included) admits host, project and
 * any keys; an environment variable and every other property source admit none.
 *
 * <p>Implements FR5, FR6, FR7, NFR-S1 of add-project-registry.
 */
public enum SettingSource {
    /** {@code GNOMISH_HOME/factory.yaml}. */
    HOST_FILE,
    /** The resolved project's {@code project.yaml}, its {@code factory:} block. */
    PROJECT_FILE,
    /** {@code --factory.key=value} or {@code -Dfactory.key=value}. */
    COMMAND_LINE,
    /** A {@code FACTORY_*} environment variable. */
    ENVIRONMENT,
    /** Any other property source: an {@code application.yaml} beside the jar, {@code SPRING_APPLICATION_JSON}. */
    OTHER;

    /** Whether a key of {@code level} may be set here. */
    public boolean admits(Level level) {
        return switch (this) {
            case HOST_FILE -> level == Level.HOST || level == Level.ANY;
            case PROJECT_FILE -> level != Level.HOST;
            case COMMAND_LINE -> level != Level.SANDBOX_BOUNDARY;
            case ENVIRONMENT, OTHER -> false;
        };
    }
}
