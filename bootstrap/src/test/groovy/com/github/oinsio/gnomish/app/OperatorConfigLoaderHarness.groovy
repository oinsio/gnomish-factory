package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.OperatorFile
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.SpringApplication
import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Drives {@link OperatorConfigLoader} the way Spring Boot does, with every input in the spec's
 * hands: a factory home of its own, a command line, the environment variables and system properties
 * the process would carry (the real ones never reach it), the file reads counted, the error
 * console scripted, and the bootstrap context closed into a fresh application context so the beans
 * the loader hands over can be read back.
 *
 * <p>Test support for add-project-registry (FR3, FR5, FR7, NFR-P1).
 */
final class OperatorConfigLoaderHarness {

    final FactoryHome home
    final Path clones
    final Map<Path, Integer> reads = [:]
    final ScriptedConsoleIO console = new ScriptedConsoleIO()

    OperatorConfigLoaderHarness(Path tmp) {
        home = FactoryHome.at(tmp.resolve('home'))
        clones = tmp.resolve('src')
        Files.createDirectories(home.root())
    }

    /** A git working tree under the spec's folder. */
    Path gitTree(String name) {
        def dir = Files.createDirectories(clones.resolve(name))
        Files.createDirectories(dir.resolve('.git'))
        dir
    }

    /** Registers {@code clone} to {@code project} through the production registry. */
    RegisteredClone register(String project, Path clone) {
        ProjectRegistry.scan(home).add(new ProjectName(project), clone)
    }

    /** Appends {@code yaml} to {@code project}'s file. */
    void projectConfig(String project, String yaml) {
        def file = home.project(new ProjectName(project)).config()
        Files.writeString(file, Files.readString(file) + yaml)
    }

    void hostConfig(String yaml) {
        Files.writeString(home.hostConfig(), yaml)
    }

    /**
     * Runs the loader over {@code args}.
     *
     * @param variables the process's environment variables
     * @param systemProperties JVM system properties besides {@code GNOMISH_HOME}
     * @param others further sources, as Spring's own configuration data would add them
     * @return the prepared environment and the context the bootstrap context closed into
     */
    Loaded load(
            List<String> args,
            Map<String, Object> variables = [:],
            Map<String, Object> systemProperties = [:],
            List<PropertySource<?>> others = []) {
        def bootstrap = new DefaultBootstrapContext()
        bootstrap.register(ApplicationArguments,
                BootstrapRegistry.InstanceSupplier.of(new DefaultApplicationArguments(args as String[])))
        def environment = environment(args, variables, systemProperties)
        others.each { environment.propertySources.addLast(it) }
        OperatorFile.Reader counting = { Path file ->
            reads[file] = (reads[file] ?: 0) + 1
            Files.readString(file)
        } as OperatorFile.Reader
        new OperatorConfigLoader(bootstrap, counting, console).postProcessEnvironment(environment, new SpringApplication())
        def context = new GenericApplicationContext()
        bootstrap.close(context)
        context.refresh()
        new Loaded(environment, context)
    }

    /** What one loader run produced. */
    static final class Loaded {
        final StandardEnvironment environment
        final GenericApplicationContext context

        Loaded(StandardEnvironment environment, GenericApplicationContext context) {
            this.environment = environment
            this.context = context
        }

        String get(String key) {
            environment.getProperty(key)
        }
    }

    private StandardEnvironment environment(
            List<String> args, Map<String, Object> variables, Map<String, Object> systemProperties) {
        def environment = new StandardEnvironment()
        def sources = environment.propertySources
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                variables))
        sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                [(FactoryHome.HOME_VARIABLE): home.root().toString()] + systemProperties))
        if (args) {
            sources.addFirst(new SimpleCommandLinePropertySource(args as String[]))
        }
        environment
    }
}
