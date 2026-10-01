package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.ProjectLayout;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.project.RegisteredProject;
import com.github.oinsio.gnomish.config.EffectiveConfiguration;
import java.util.List;

/**
 * The text {@code gnomish project} prints: the clone {@code add} registered, the projects {@code
 * list} finds, and the paths and effective configuration {@code show} reports (FR2, FR4, NFR-O1).
 *
 * <p>Implements FR2, FR4, NFR-O1 of add-project-registry.
 */
final class ProjectReport {

    private static final String NL = ConsoleIO.LINE_END;

    private ProjectReport() {}

    static String added(RegisteredClone clone) {
        return "registered clone " + clone.cloneName() + " of project " + clone.project() + ": " + clone.clonePath()
                + NL + "  project file: " + clone.layout().config() + NL;
    }

    static String list(FactoryHome home, List<RegisteredProject> projects) {
        if (projects.isEmpty()) {
            return "no projects registered in " + home.projects()
                    + "; register a clone with: gnomish project add <name> --dir=<clone>" + NL;
        }
        StringBuilder text = new StringBuilder();
        for (RegisteredProject project : projects) {
            text.append(project.name())
                    .append(": ")
                    .append(project.layout().dir())
                    .append(NL);
            for (RegisteredClone clone : project.clones()) {
                text.append("  ")
                        .append(clone.cloneName())
                        .append(": ")
                        .append(clone.clonePath())
                        .append(NL);
            }
        }
        return text.toString();
    }

    static String show(RegisteredProject project, String instance, List<EffectiveConfiguration.Row> rows) {
        ProjectLayout layout = project.layout();
        StringBuilder text = new StringBuilder()
                .append("project ")
                .append(project.name())
                .append(": ")
                .append(layout.dir())
                .append(NL)
                .append("  project file: ")
                .append(layout.config())
                .append(NL)
                .append("  log file: ")
                .append(layout.logFile(instance))
                .append(NL)
                .append("  serve folder: ")
                .append(layout.serveDir(instance))
                .append(NL);
        for (RegisteredClone clone : project.clones()) {
            text.append("  clone ")
                    .append(clone.cloneName())
                    .append(": ")
                    .append(clone.clonePath())
                    .append(" (worktrees: ")
                    .append(clone.worktrees())
                    .append(')')
                    .append(NL);
        }
        text.append("configuration:").append(NL);
        for (EffectiveConfiguration.Row row : rows) {
            text.append("  ")
                    .append(row.key())
                    .append(" = ")
                    .append(row.value())
                    .append("  [")
                    .append(row.level())
                    .append("]  ")
                    .append(row.origin());
            if (row.overrides() != null) {
                text.append(" (overrides ").append(row.overrides()).append(')');
            }
            text.append(NL);
        }
        return text.toString();
    }
}
