package com.github.oinsio.gnomish.app.project;

import java.util.List;

/**
 * One registered project as its file lists it: the project's layout under the factory home and
 * every clone registered to it, in file order (FR2, FR4).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR4 of add-project-registry.
 *
 * @param layout the project's folder and the paths inside it
 * @param clones the project's clones, each carrying {@code layout}
 */
public record RegisteredProject(ProjectLayout layout, List<RegisteredClone> clones) {

    public RegisteredProject {
        clones = List.copyOf(clones);
    }

    /** The project's name. */
    public ProjectName name() {
        return layout.name();
    }
}
