package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.project.ProjectName;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed form of one {@code gnomish project} invocation, produced by {@link
 * ProjectArgumentsParser}: the verb, the project it names, and — for {@code add} only — the clone
 * directory to register.
 *
 * <p>Implements FR2, FR4 of add-project-registry.
 *
 * @param verb {@code add}, {@code list} or {@code show}
 * @param name the project named on the command line; present for {@code add}, optional for {@code
 *     show}, absent for {@code list}
 * @param dir the directory {@code add} registers, absolute and normalized (FR7 of
 *     fix-operator-blockers); {@code null} for the verbs that register nothing
 */
record ProjectArguments(
        Verb verb, @Nullable ProjectName name, @Nullable Path dir) {

    ProjectArguments {
        if (dir != null) {
            ArgumentsParsingSupport.requireAbsoluteDir(dir);
        }
    }

    /** The {@code project} verbs. */
    enum Verb {
        /** {@code gnomish project add <name> [--dir=<clone>]} — registers a clone (FR2). */
        ADD,
        /** {@code gnomish project list} — every project with its clones (FR4). */
        LIST,
        /** {@code gnomish project show [<name>]} — paths and effective configuration (FR4). */
        SHOW
    }
}
