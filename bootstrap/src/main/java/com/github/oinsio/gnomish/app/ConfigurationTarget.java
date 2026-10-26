package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.app.project.ProjectRegistry;
import com.github.oinsio.gnomish.app.project.ProjectResolution;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * What a command line asks the operator configuration to be resolved for (FR3, design D9 of
 * add-project-registry): the directory whose registered clone selects the project, or a project
 * named outright. Read once, by {@link OperatorConfigLoader}, before any bean exists; a {@code
 * --dir} that cannot be read, or a directory no project registers, is a refusal the loader reports
 * with the configuration's violations.
 *
 * <p>{@code doctor} works in the project of {@code --dir} or of the working directory, and a
 * directory no project registers leaves it project-less rather than refused: {@link #resolution}
 * keeps the registry's answer, refusal included, for the registration check (FR1, FR2, FR7 of
 * add-doctor-command).
 *
 * <p>A class rather than a record: PIT cannot hot-swap a mutant into a record's methods
 * (hcoles/pitest#1285, {@code testing.md}), and every method here holds a decision.
 *
 * <p>Implements FR3 of add-project-registry; FR1, FR2, FR7 of add-doctor-command.
 */
@NullMarked
final class ConfigurationTarget {

    private static final ConfigurationTarget NONE = new ConfigurationTarget(null, null, false);

    private final @Nullable Path dir;
    private final @Nullable ProjectName name;
    private final boolean named;

    /**
     * @param dir the directory to resolve, or {@code null} when the command works in none
     * @param name the project the command names outright, or {@code null}
     * @param named whether {@code --dir} named the directory, rather than the working directory
     *     standing in for it; read by {@code doctor}'s {@link #resolution} only
     */
    private ConfigurationTarget(@Nullable Path dir, @Nullable ProjectName name, boolean named) {
        this.dir = dir;
        this.name = name;
        this.named = named;
    }

    /** The project the command names outright, or {@code null}. */
    @Nullable
    ProjectName name() {
        return name;
    }

    /**
     * Reads the target from the command line.
     *
     * @param refusals collects the usage error a malformed command line raises
     * @return the target; {@link #NONE} for the empty command line or a refused one
     */
    static ConfigurationTarget of(ApplicationArguments args, List<String> refusals) {
        if (args.getSourceArgs().length == 0) {
            return NONE;
        }
        try {
            Subcommand subcommand = Subcommand.parse(args);
            return switch (subcommand) {
                case DOCTOR ->
                    new ConfigurationTarget(
                            ArgumentsParsingSupport.projectDir(args), null, ArgumentsParsingSupport.namesDir(args));
                case PROJECT -> project(args);
                case STATUS, USAGE ->
                    dir(ArgumentsParsingSupport.requiredProjectDir(
                            args, subcommand.name().toLowerCase(Locale.ROOT)));
                case RUN, TAKE, SERVE, BOARD, DASHBOARD -> dir(ArgumentsParsingSupport.projectDir(args));
            };
        } catch (UsageException e) {
            refusals.add(String.valueOf(e.getMessage()));
            return NONE;
        }
    }

    /**
     * The clone {@link #dir} names, or {@code null} when there is none or no project registers it.
     *
     * @param refusals collects the unregistered directory
     */
    @Nullable
    RegisteredClone resolve(ProjectRegistry registry, List<String> refusals) {
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

    /**
     * {@code doctor}'s form of {@link #resolve}, refusing nothing: the registry's answer for
     * {@link #dir}, its refusal kept as the registration check's detail (FR1, FR2 of
     * add-doctor-command).
     *
     * @param registry the registry, or {@code null} when it could not be read
     * @return the resolution; {@code null} when the command line was refused and names no directory
     */
    @Nullable
    ProjectResolution resolution(@Nullable ProjectRegistry registry) {
        if (dir == null) {
            return null;
        }
        if (registry == null) {
            return new ProjectResolution.Unread(dir, named);
        }
        try {
            return new ProjectResolution.Registered(registry.resolve(dir));
        } catch (UsageException e) {
            return new ProjectResolution.Unregistered(dir, named, String.valueOf(e.getMessage()));
        }
    }

    private static ConfigurationTarget dir(Path dir) {
        return new ConfigurationTarget(dir, null, false);
    }

    /** {@code add}/{@code list} resolve nothing; {@code show <name>} names its project, bare {@code show} its clone. */
    private static ConfigurationTarget project(ApplicationArguments args) {
        ProjectArguments project = new ProjectArgumentsParser().parse(args);
        return switch (project.verb()) {
            case ADD, LIST -> NONE;
            case SHOW ->
                project.name() != null
                        ? new ConfigurationTarget(null, project.name(), false)
                        : dir(ArgumentsParsingSupport.projectDir(args));
        };
    }
}
