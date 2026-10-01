package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.app.ConfigurationViolationsException;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.OperatorFile;
import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.app.project.ProjectRegistry;
import com.github.oinsio.gnomish.app.project.RegisteredProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * One startup's operator configuration, checked whole (FR5, FR6, FR7): the host file's and the
 * resolved project's {@code factory:} blocks, every other source Spring assembled, and the
 * permissions of every operator file — each finding collected with the refusals the project
 * resolution already made, and the lot thrown as one report. Only a configuration with no finding
 * yields its two blocks, placed into the environment by {@link OperatorSources#add}.
 *
 * <p>Implements FR5, FR6, FR7, NFR-S1, NFR-S2, NFR-O2 of add-project-registry.
 */
public final class OperatorConfigCheck {

    /** The environment's name for the host file's block. */
    public static final String HOST_SOURCE = "operator host file";
    /** The environment's name for the project file's block. */
    public static final String PROJECT_SOURCE = "operator project file";

    private final FactoryHome home;
    private final OperatorFile.Reader reader;

    /**
     * @param home the factory home the files live under
     * @param reader reads the host file; the project files were read by the registry
     */
    public OperatorConfigCheck(FactoryHome home, OperatorFile.Reader reader) {
        this.home = home;
        this.reader = reader;
    }

    /** The two checked blocks, each {@code null} when its file sets no {@code factory.*} key. */
    public record Blocks(
            @Nullable PropertySource<?> project, @Nullable PropertySource<?> host) {}

    /**
     * Checks every source.
     *
     * @param registry the registry, or {@code null} when it could not be read (a refusal says why)
     * @param project the project whose block applies, or {@code null} for a project-less command
     * @param refusals what resolving the project already refused, reported first
     * @throws ConfigurationViolationsException listing every finding, when there is one
     */
    public Blocks check(
            ConfigurableEnvironment environment,
            @Nullable ProjectRegistry registry,
            @Nullable ProjectName project,
            List<String> refusals) {
        List<RegisteredProject> projects = registry == null ? List.of() : registry.projects();
        ConfigViolations violations = new ConfigViolations(
                ConfigLevels.application(),
                new ConfigPlaces(
                        home,
                        project,
                        projects.stream().map(RegisteredProject::name).toList()));
        refusals.forEach(violations::refusal);
        PropertySource<?> host = hostBlock(violations);
        PropertySource<?> projectBlock = registry == null || project == null
                ? null
                : OperatorSources.factoryBlock(
                        PROJECT_SOURCE,
                        registry.document(project),
                        SettingSource.PROJECT_FILE,
                        key -> key.startsWith("clones."),
                        "clones: and factory.* keys",
                        violations);
        OperatorSources.check(environment, violations);
        List<Path> files = new ArrayList<>(List.of(home.hostConfig()));
        projects.forEach(p -> files.add(p.layout().config()));
        OperatorSources.checkWritable(files, violations);
        if (!violations.isEmpty()) {
            throw violations.exception();
        }
        return new Blocks(projectBlock, host);
    }

    private @Nullable PropertySource<?> hostBlock(ConfigViolations violations) {
        try {
            String text = OperatorFile.read(reader, home.hostConfig());
            return OperatorSources.factoryBlock(
                    HOST_SOURCE,
                    OperatorFile.parse(home.hostConfig(), text),
                    SettingSource.HOST_FILE,
                    _ -> false,
                    "factory.* keys",
                    violations);
        } catch (UsageException e) {
            violations.refusal(String.valueOf(e.getMessage()));
            return null;
        }
    }
}
