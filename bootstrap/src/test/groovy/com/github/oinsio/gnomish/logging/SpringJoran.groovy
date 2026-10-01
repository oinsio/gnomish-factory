package com.github.oinsio.gnomish.logging

import ch.qos.logback.classic.joran.JoranConfigurator
import com.github.oinsio.gnomish.config.OperatorLogFile
import org.springframework.boot.logging.LoggingInitializationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * Spring Boot's Joran configurator — the one that gives {@code logback-spring.xml} its
 * {@code <springProperty>} tag — over an environment that carries the log file the operator
 * configuration loader would publish (FR11 of add-project-registry). A spec that parses the
 * production file with plain Joran sees the tag as an unknown property and an undefined file
 * variable, which Logback turns into a {@code GNOMISH_LOG_FILE_IS_UNDEFINED} file in the working
 * directory.
 *
 * <p>The configurator is package-private in Spring Boot and no public API configures a context
 * other than the JVM's global one through it, so it is constructed reflectively; the
 * constructor's single argument is its documented input.
 *
 * <p>Test support for FR11 of add-project-registry.
 */
final class SpringJoran {

    private SpringJoran() {}

    /**
     * @param logFile the absolute log file path, as {@code OperatorLogFile} publishes it
     * @return a configurator whose {@code <springProperty>} reads {@code logFile}
     */
    static JoranConfigurator publishing(String logFile) {
        StandardEnvironment environment = new StandardEnvironment()
        environment.propertySources.addFirst(new MapPropertySource('published', [(OperatorLogFile.PROPERTY): logFile]))
        def type = Class.forName('org.springframework.boot.logging.logback.SpringBootJoranConfigurator')
        def constructor = type.getDeclaredConstructor(LoggingInitializationContext)
        constructor.accessible = true
        return (JoranConfigurator) constructor.newInstance(new LoggingInitializationContext(environment))
    }
}
