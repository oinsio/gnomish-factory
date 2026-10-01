package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.app.project.ProjectRegistry;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.project.RegisteredProject;
import com.github.oinsio.gnomish.config.ConfigLevels;
import com.github.oinsio.gnomish.config.EffectiveConfiguration;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

/**
 * {@code gnomish project add|list|show} (FR2, FR4, U5): registers a clone, lists the registered
 * projects, and shows one project's paths with every effective {@code factory.*} value and its
 * origin. The registry is the one the configuration loader read while the environment was prepared
 * (design D9): every project file is read once per process (NFR-P1).
 *
 * <p>{@code show} without a name reports the project the operator's {@code --dir} resolved to: the
 * {@link RegisteredClone} the configuration loader registers (design D9) — never a second
 * resolution here. Where no clone was resolved it names the forms that work.
 *
 * <p>Implements FR2, FR4, NFR-O1 of add-project-registry.
 */
@NullMarked
@Component
final class ProjectCommand {

    private final ProjectArgumentsParser parser = new ProjectArgumentsParser();
    private final FactoryHome home;
    private final ProjectRegistry registry;
    private final FactoryProperties properties;
    private final ConfigurableEnvironment environment;
    private final ObjectProvider<RegisteredClone> resolvedClone;
    private final ConsoleIO console;

    ProjectCommand(
            FactoryHome home,
            ProjectRegistry registry,
            FactoryProperties properties,
            ConfigurableEnvironment environment,
            ObjectProvider<RegisteredClone> resolvedClone,
            ConsoleIO console) {
        this.home = home;
        this.registry = registry;
        this.properties = properties;
        this.environment = environment;
        this.resolvedClone = resolvedClone;
        this.console = console;
    }

    /**
     * @param args the raw application arguments, including the leading {@code project} token
     * @throws UsageException if the arguments are malformed, a registration is refused, or {@code
     *     show} names no registered project
     */
    void run(ApplicationArguments args) {
        ProjectArguments arguments = parser.parse(args);
        String report =
                switch (arguments.verb()) {
                    case ADD ->
                        ProjectReport.added(registry.add(
                                Objects.requireNonNull(arguments.name()), Objects.requireNonNull(arguments.dir())));
                    case LIST -> ProjectReport.list(home, registry.projects());
                    case SHOW ->
                        ProjectReport.show(
                                shown(arguments.name()),
                                properties.instanceName(),
                                EffectiveConfiguration.of(environment, ConfigLevels.application(), home));
                };
        console.print(report);
    }

    private RegisteredProject shown(@Nullable ProjectName name) {
        ProjectName wanted = name != null ? name : resolvedProject();
        return registry.project(wanted)
                .orElseThrow(() -> new UsageException("no project named " + wanted
                        + " is registered; registered: "
                        + registry.projects().stream()
                                .map(p -> p.name().value())
                                .collect(Collectors.joining(", "))));
    }

    private ProjectName resolvedProject() {
        RegisteredClone clone = resolvedClone.getIfAvailable();
        if (clone == null) {
            throw new UsageException("no registered clone was resolved: run 'gnomish project show <name>',"
                    + " or 'gnomish project show --dir=<registered clone>'");
        }
        return clone.project();
    }
}
