package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.OperatorFile;
import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.app.project.ProjectRegistry;
import com.github.oinsio.gnomish.app.project.ProjectResolution;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.config.ListShadowing;
import com.github.oinsio.gnomish.config.OperatorConfigCheck;
import com.github.oinsio.gnomish.config.OperatorLogFile;
import com.github.oinsio.gnomish.config.OperatorSources;
import java.util.ArrayList;
import java.util.List;
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
 * FR11) — before Logback starts, which is why it is decided here. Once the sources are in place, a
 * list a higher source sets replaces the lower sources' list whole ({@link ListShadowing}).
 *
 * <p>{@code doctor} runs in collect mode, chosen by {@link Subcommand#namesDoctor} before anything
 * else is read (FR7, design D1, D2 of add-doctor-command): the same {@link ConfigurationViolations}
 * the refusal is built on — the check's and the log file's — reach the context as the singleton
 * {@value #VIOLATIONS_BEAN} instead of being thrown, the sources are placed and the log file is
 * published as for any command, so the remaining checks still run. In that mode a directory no
 * project registers is no violation: the registry's answer for it, refusal included, reaches the
 * context as the singleton {@value #RESOLUTION_BEAN} ({@link ConfigurationTarget#resolution}), the
 * one resolution of {@code --dir} the registration check reports (FR1, FR2).
 *
 * <p>The directory is resolved once per process (FR3, D9): the target from {@link
 * ConfigurationTarget#of}, the clone from {@link ProjectRegistry#resolve}. The
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
 * add-project-registry; FR7 of add-doctor-command.
 */
@NullMarked
public final class OperatorConfigLoader implements EnvironmentPostProcessor, Ordered {

    /** Right after Spring's own configuration data is loaded. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /** The name of the {@link ConfigurationViolations} singleton {@code doctor} reads. */
    public static final String VIOLATIONS_BEAN = "configurationViolations";

    /** The name of the {@link ProjectResolution} singleton {@code doctor} reads. */
    public static final String RESOLUTION_BEAN = "projectResolution";

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
        boolean collect = Subcommand.namesDoctor(args);
        FactoryHome home = FactoryHome.from(environment::getProperty);
        List<String> refusals = new ArrayList<>();
        ConfigurationTarget target = ConfigurationTarget.of(args, refusals);
        ProjectRegistry registry = scan(home, refusals);
        ProjectResolution resolution = collect ? target.resolution(registry) : null;
        RegisteredClone clone = collect ? registered(resolution) : resolve(target, registry, refusals);
        ProjectName project = clone != null ? clone.project() : target.name();
        OperatorConfigCheck.Checked checked =
                new OperatorConfigCheck(home, reader).check(environment, registry, project, refusals);
        refuseUnless(collect, checked.violations());
        OperatorSources.add(environment, checked.project(), checked.host());
        ListShadowing.apply(environment.getPropertySources());
        ConfigurationViolations logFile = OperatorLogFile.publish(environment, home, project);
        refuseUnless(collect, logFile);
        Collected collected = collect ? new Collected(checked.violations().plus(logFile), resolution) : null;
        bootstrap.addCloseListener(
                event -> register(event.getApplicationContext().getBeanFactory(), home, registry, clone, collected));
    }

    /** What collect mode publishes for {@code doctor}: the violations, and the directory's resolution. */
    private record Collected(
            ConfigurationViolations violations, @Nullable ProjectResolution resolution) {}

    /** The refusing commands' clone: none without a registry, else the target's, its refusal collected. */
    private static @Nullable RegisteredClone resolve(
            ConfigurationTarget target, @Nullable ProjectRegistry registry, List<String> refusals) {
        return registry == null ? null : target.resolve(registry, refusals);
    }

    /** {@code doctor}'s clone: the resolution's, when it is one. */
    private static @Nullable RegisteredClone registered(@Nullable ProjectResolution resolution) {
        return resolution instanceof ProjectResolution.Registered registered ? registered.registeredClone() : null;
    }

    /** The refusing form: prints the report once and throws it, unless {@code doctor} collects. */
    private void refuseUnless(boolean collect, ConfigurationViolations violations) {
        if (collect || violations.isEmpty()) {
            return;
        }
        ConfigurationViolationsException report = violations.exception();
        errorConsole.print(report.getMessage() + ConsoleIO.LINE_END);
        throw report;
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

    /** A registry is absent only in collect mode, where its scan failure is a published violation. */
    private static void register(
            ConfigurableListableBeanFactory beans,
            FactoryHome home,
            @Nullable ProjectRegistry registry,
            @Nullable RegisteredClone clone,
            @Nullable Collected collected) {
        beans.registerSingleton("factoryHome", home);
        if (registry != null) {
            beans.registerSingleton("projectRegistry", registry);
        }
        if (clone != null) {
            beans.registerSingleton("registeredClone", clone);
        }
        if (collected != null) {
            beans.registerSingleton(VIOLATIONS_BEAN, collected.violations());
            if (collected.resolution() != null) {
                beans.registerSingleton(RESOLUTION_BEAN, collected.resolution());
            }
        }
    }
}
