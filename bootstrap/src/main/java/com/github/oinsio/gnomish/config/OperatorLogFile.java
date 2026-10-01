package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.ConfigurationViolationsException;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectName;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The log file of this process, decided before Logback starts (FR11, design D1, D6): a command
 * resolved to a registered project logs to {@code projects/<name>/logs/<instance>.log}, a
 * project-less command to {@code logs/factory.log} — both paths from {@link FactoryHome}, the one
 * owner of operator paths. The file is published as the internal property {@value #PROPERTY},
 * which {@code logback-spring.xml} reads with {@code <springProperty>}, beside the same path without
 * its {@code .log} extension as {@value #ARCHIVE_BASE_PROPERTY}, so a rolled segment is named
 * {@code <instance>.<date>.<n>.log} and keeps the extension; no log-directory variable exists
 * besides them.
 *
 * <p>Only the instance name is bound here, with the binder the context uses later, so its relaxed
 * spellings have one owner; its default and its blank check come from {@link FactoryProperties}.
 * Every other {@code factory.*} key is left to the context's own binding, which reports a bad value
 * of it — this step must not fail on a key it does not read.
 *
 * <p>Implements FR11, UX3 of add-project-registry.
 */
public final class OperatorLogFile {

    /** The internal property {@code logback-spring.xml} reads the log file from. */
    public static final String PROPERTY = "gnomish.internal.log-file";

    /** The internal property carrying the log file without its extension, for rolled segments. */
    public static final String ARCHIVE_BASE_PROPERTY = "gnomish.internal.log-archive-base";

    /** The environment's name for the source carrying {@link #PROPERTY}. */
    public static final String SOURCE = "operator log file";

    private static final String INSTANCE_NAME = "factory.instance-name";

    private OperatorLogFile() {}

    /**
     * Publishes the log file ahead of every other source, so no operator setting can move it.
     *
     * @param environment the environment the operator sources were already added to
     * @param home the factory home
     * @param project the project the command resolved to, or {@code null} for a project-less one
     * @throws ConfigurationViolationsException if the instance name cannot name a file
     */
    public static void publish(ConfigurableEnvironment environment, FactoryHome home, @Nullable ProjectName project) {
        Path file = project == null ? home.hostLogFile() : projectLogFile(environment, home, project);
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, properties(file)));
    }

    /**
     * The internal properties {@code logback-spring.xml} reads for {@code file}.
     *
     * @param file a log file, named {@code <stem>.log} by {@link FactoryHome}
     * @return {@link #PROPERTY} and {@link #ARCHIVE_BASE_PROPERTY}
     */
    public static Map<String, Object> properties(Path file) {
        String path = file.toString();
        return Map.of(PROPERTY, path, ARCHIVE_BASE_PROPERTY, path.replaceFirst("\\.log$", ""));
    }

    private static Path projectLogFile(ConfigurableEnvironment environment, FactoryHome home, ProjectName project) {
        @Nullable
        String configured =
                Binder.get(environment).bind(INSTANCE_NAME, String.class).orElse(null);
        try {
            // the six-argument constructor resolves the unset and blank cases as the binding does
            String instance = new FactoryProperties(configured, null, null, null, null, null).instanceName();
            return home.project(project).logFile(instance);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationViolationsException(List.of(INSTANCE_NAME + ": " + e.getMessage()
                    + " — the instance name is a folder and file name under the project's logs and serve folders"));
        }
    }
}
