package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectName;
import com.github.oinsio.gnomish.operatorconfig.Level;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The words a violation line uses for where a key of each level belongs (UX1): the level's name,
 * the places it is read from, and the concrete files to move it to — the resolved project's own
 * file when a project was resolved, otherwise the project file's pattern with the registered
 * projects listed.
 *
 * <p>Implements FR7, NFR-O2, UX1 of add-project-registry.
 */
public final class ConfigPlaces {

    private final FactoryHome home;
    private final @Nullable ProjectName project;
    private final List<ProjectName> registered;

    /**
     * @param home the factory home the files are named under
     * @param project the resolved project, or {@code null} when none was resolved
     * @param registered every registered project, listed when no project was resolved
     */
    public ConfigPlaces(FactoryHome home, @Nullable ProjectName project, List<ProjectName> registered) {
        this.home = home;
        this.project = project;
        this.registered = List.copyOf(registered);
    }

    /** The level as the operator reads it: {@code host}, {@code project}, {@code any}, {@code sandbox-boundary}. */
    static String label(Level level) {
        return level.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Where a key of {@code level} is read from, e.g. "a project's own file or the command line". */
    String readFrom(Level level) {
        return switch (level) {
            case HOST -> "factory.yaml or the command line";
            case PROJECT -> "a project's own file or the command line";
            case ANY -> "factory.yaml, a project's own file or the command line";
            case SANDBOX_BOUNDARY -> "a project's own file";
        };
    }

    /** The fix for a key of {@code level} set in the wrong place: the file or files to move it to. */
    String moveTo(Level level) {
        return "move it to " + files(level);
    }

    /**
     * The fix for a {@code FACTORY_*} variable: the equivalent file line, and the command-line form
     * where the level admits it.
     *
     * @param key the {@code factory.*} key the variable spells
     * @param value the variable's value
     */
    String equivalent(Level level, String key, String value) {
        String fix = "set '" + key + ": " + value + "' in " + files(level);
        return SettingSource.COMMAND_LINE.admits(level) ? fix + ", or pass --" + key + "=" + value : fix;
    }

    private String files(Level level) {
        return switch (level) {
            case HOST -> home.hostConfig().toString();
            case PROJECT, SANDBOX_BOUNDARY -> projectFile();
            case ANY -> projectFile() + " or " + home.hostConfig();
        };
    }

    private String projectFile() {
        if (project != null) {
            return home.project(project).config().toString();
        }
        String pattern =
                home.projects().resolve("<name>").resolve("project.yaml").toString();
        return registered.isEmpty()
                ? pattern + " (no project is registered yet: gnomish project add <name> --dir=<clone>)"
                : pattern + " (registered: "
                        + registered.stream().map(ProjectName::value).collect(Collectors.joining(", ")) + ")";
    }
}
