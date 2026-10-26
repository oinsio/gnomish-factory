package com.github.oinsio.gnomish.app.project;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One leftover of the pre-registry folder layout found at the factory home's root, and where the
 * registry layout keeps that kind of state now (add-project-registry, NG3: no automatic migration).
 * Found only by {@link FactoryHome#legacyFolders()}; the doctor reports it as a warning whose fix
 * names {@code successor}.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR1 of add-doctor-command.
 *
 * @param path the leftover file or folder; absolute
 * @param successor where that state lives in the registry layout, relative to the home
 */
public record LegacyEntry(Path path, String successor) {

    public LegacyEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(successor, "successor");
    }
}
