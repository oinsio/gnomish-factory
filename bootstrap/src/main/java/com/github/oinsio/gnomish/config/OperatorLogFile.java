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
 * which {@code logback-spring.xml} reads with {@code <springProperty>}; no log-directory variable
 * exists besides it.
 *
 * <p>The instance name is bound from the environment the way the context binds it later — through
 * {@link FactoryProperties}, so its default and its relaxed spellings have one owner.
 *
 * <p>Implements FR11, UX3 of add-project-registry.
 */
public final class OperatorLogFile {

    /** The internal property {@code logback-spring.xml} reads the log file from. */
    public static final String PROPERTY = "gnomish.internal.log-file";

    /** The environment's name for the source carrying {@link #PROPERTY}. */
    public static final String SOURCE = "operator log file";

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
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, Map.of(PROPERTY, file.toString())));
    }

    private static Path projectLogFile(ConfigurableEnvironment environment, FactoryHome home, ProjectName project) {
        String instance = Binder.get(environment)
                .bindOrCreate("factory", FactoryProperties.class)
                .instanceName();
        try {
            return home.project(project).logFile(instance);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationViolationsException(List.of("factory.instance-name: " + e.getMessage()
                    + " — the instance name is a folder and file name under the project's logs and serve folders"));
        }
    }
}
