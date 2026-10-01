package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.OperatorFile;
import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.app.project.ProjectRegistry;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.config.OperatorConfigCheck;
import com.github.oinsio.gnomish.config.OperatorLogFile;
import com.github.oinsio.gnomish.config.OperatorSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Assembles and checks the operator configuration before any bean exists (FR5, FR7, design D6,
 * D9): reads the command line from the bootstrap context, resolves the project the command works
 * in, adds the host file {@code factory.yaml} and the project's {@code factory:} block below the
 * command line, and checks every source against the level table. Every violation — a key where
 * its level forbids, an unknown key, a {@code FACTORY_*} variable, a file others may write, an
 * unregistered {@code --dir} — is collected by {@link OperatorConfigCheck}, printed once to
 * standard error, and thrown as one {@link ConfigurationViolationsException} (exit 2). A
 * configuration with no violation also decides the process's log file ({@link OperatorLogFile},
 * FR11) — before Logback starts, which is why it is decided here.
 *
 * <p>The directory is resolved once per process (FR3, D9): the subcommand from {@link
 * Subcommand#parse}, the directory from {@link ArgumentsParsingSupport#projectDir} / {@link
 * ArgumentsParsingSupport#requiredProjectDir}, the clone from {@link ProjectRegistry#resolve}. The
 * resolved {@link RegisteredClone}, the {@link FactoryHome} it was resolved against and the
 * {@link ProjectRegistry} it was read from reach the context as singletons, registered when the
 * bootstrap context closes — before the context refreshes. {@code project add}, {@code project
 * list} and the empty command line (the no-op a test context boots with) resolve no project;
 * {@code project show <name>} takes the named one.
 *
 * <p>Registered in {@code META-INF/spring.factories}; ordered after {@link
 * ConfigDataEnvironmentPostProcessor}, so every source Spring loads is present to be checked.
 *
 * <p>Implements FR3, FR5, FR6, FR7, FR11, NFR-S1, NFR-S2, NFR-R1, NFR-O2, NFR-P1 of
 * add-project-registry.
 */
@NullMarked
public final class OperatorConfigLoader implements EnvironmentPostProcessor, Ordered {

    /** Right after Spring's own configuration data is loaded. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    private final ConfigurableBootstrapContext bootstrap;
    private final OperatorFile.Reader reader;
    private final ConsoleIO errorConsole;

    /** Instantiated by Spring Boot's factory loader, which supplies the bootstrap context. */
    public OperatorConfigLoader(ConfigurableBootstrapContext bootstrap) {
        this(bootstrap, OperatorFile.FILESYSTEM, new SystemConsoleIO(System.in, System.err));
    }

    /** Same, with the file reads and the error console seamed for specs. */
    OperatorConfigLoader(ConfigurableBootstrapContext bootstrap, OperatorFile.Reader reader, ConsoleIO errorConsole) {
        this.bootstrap = bootstrap;
        this.reader = reader;
        this.errorConsole = errorConsole;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ApplicationArguments args = bootstrap.getOrElse(ApplicationArguments.class, null);
        if (args == null) {
            throw new IllegalStateException("the command line was not registered in the bootstrap context:"
                    + " boot the factory through CommandExit, which registers it");
        }
        FactoryHome home = FactoryHome.from(environment::getProperty);
        List<String> refusals = new ArrayList<>();
        Target target = target(args, refusals);
        ProjectRegistry registry = scan(home, refusals);
        RegisteredClone clone = registry == null ? null : resolve(registry, target.dir(), refusals);
        ProjectName project = clone != null ? clone.project() : target.name();
        try {
            OperatorConfigCheck.Blocks blocks =
                    new OperatorConfigCheck(home, reader).check(environment, registry, project, refusals);
            OperatorSources.add(environment, blocks.project(), blocks.host());
            OperatorLogFile.publish(environment, home, project);
        } catch (ConfigurationViolationsException report) {
            errorConsole.print(report.getMessage() + ConsoleIO.LINE_END);
            throw report;
        }
        ProjectRegistry scanned = Objects.requireNonNull(registry, "a registry that failed to scan is a violation");
        bootstrap.addCloseListener(
                event -> register(event.getApplicationContext().getBeanFactory(), home, scanned, clone));
    }

    /** What the command line asks the configuration to be resolved for. */
    private record Target(@Nullable Path dir, @Nullable ProjectName name) {}

    private static Target target(ApplicationArguments args, List<String> refusals) {
        if (args.getSourceArgs().length == 0) {
            return new Target(null, null);
        }
        try {
            Subcommand subcommand = Subcommand.parse(args);
            return switch (subcommand) {
                case PROJECT -> projectTarget(args);
                case STATUS, USAGE ->
                    new Target(
                            ArgumentsParsingSupport.requiredProjectDir(
                                    args, subcommand.name().toLowerCase(Locale.ROOT)),
                            null);
                case RUN, TAKE, SERVE, BOARD, DASHBOARD -> new Target(ArgumentsParsingSupport.projectDir(args), null);
            };
        } catch (UsageException e) {
            refusals.add(String.valueOf(e.getMessage()));
            return new Target(null, null);
        }
    }

    /** {@code add}/{@code list} resolve nothing; {@code show <name>} names its project, bare {@code show} its clone. */
    private static Target projectTarget(ApplicationArguments args) {
        ProjectArguments project = new ProjectArgumentsParser().parse(args);
        return switch (project.verb()) {
            case ADD, LIST -> new Target(null, null);
            case SHOW ->
                project.name() != null
                        ? new Target(null, project.name())
                        : new Target(ArgumentsParsingSupport.projectDir(args), null);
        };
    }

    /** The registry, or {@code null} when a project file cannot be read — a refusal, reported with the rest. */
    private @Nullable ProjectRegistry scan(FactoryHome home, List<String> refusals) {
        try {
            return ProjectRegistry.scan(home, reader);
        } catch (UsageException e) {
            refusals.add(String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static @Nullable RegisteredClone resolve(
            ProjectRegistry registry, @Nullable Path dir, List<String> refusals) {
        if (dir == null) {
            return null;
        }
        try {
            return registry.resolve(dir);
        } catch (UsageException e) {
            refusals.add(String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static void register(
            ConfigurableListableBeanFactory beans,
            FactoryHome home,
            ProjectRegistry registry,
            @Nullable RegisteredClone clone) {
        beans.registerSingleton("factoryHome", home);
        beans.registerSingleton("projectRegistry", registry);
        if (clone != null) {
            beans.registerSingleton("registeredClone", clone);
        }
    }
}
