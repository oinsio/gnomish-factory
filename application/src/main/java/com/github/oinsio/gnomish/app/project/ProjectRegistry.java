package com.github.oinsio.gnomish.app.project;

import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.env.PropertySource;

/**
 * The project registry: every {@code projects/*}{@code /project.yaml} under the factory home, read
 * once when the registry is built (design D3, NFR-P1) — the configuration loader takes a project's
 * {@code factory:} block from the same read, through {@link #document}. It resolves an operator's directory to the
 * one {@link RegisteredClone} whose path it is — exact match of real paths, so a symlink reaches
 * its clone and a subdirectory does not (FR3, UX2) — and registers a new clone (FR2, NFR-R1).
 *
 * <p>An immutable snapshot: {@link #add} writes the file but does not change what this instance
 * resolves; a process registers at most once and exits.
 *
 * <p>Implements FR2, FR3, NFR-R1, NFR-P1, UX2 of add-project-registry.
 */
public final class ProjectRegistry {

    private static final String FALLBACK_NAME = "project";

    private final FactoryHome home;
    private final List<RegisteredProject> projects;
    private final Map<ProjectName, ProjectFolders.Read> reads;

    private ProjectRegistry(FactoryHome home, List<ProjectFolders.Read> reads) {
        this.home = home;
        this.projects = reads.stream().map(ProjectFolders.Read::project).toList();
        this.reads = reads.stream()
                .collect(Collectors.toUnmodifiableMap(r -> r.project().name(), r -> r));
    }

    /**
     * Reads every project file under {@code home}; a home with no {@code projects/} folder yet has
     * no projects.
     *
     * @throws UsageException if a project folder's name or a project file is invalid
     */
    public static ProjectRegistry scan(FactoryHome home) {
        return scan(home, OperatorFile.FILESYSTEM);
    }

    /**
     * Same as {@link #scan(FactoryHome)}, reading each project file once through {@code reader}.
     *
     * @throws UsageException if a project folder's name or a project file is invalid
     */
    public static ProjectRegistry scan(FactoryHome home, OperatorFile.Reader reader) {
        return new ProjectRegistry(home, ProjectFolders.read(home, reader));
    }

    /** Every registered project, ordered by name. */
    public List<RegisteredProject> projects() {
        return projects;
    }

    /** The registered project named {@code name}, if there is one. */
    public Optional<RegisteredProject> project(ProjectName name) {
        return projects.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * The parsed project file of {@code name} — its {@code clones:} and its {@code factory:} block,
     * each property carrying its file and line — from the read the registry was built with.
     *
     * @return the file's property sources; empty for a project that is not registered
     */
    public List<PropertySource<?>> document(ProjectName name) {
        ProjectFolders.Read read = reads.get(name);
        return read == null ? List.of() : read.document();
    }

    /**
     * The registered clone {@code dir} is, compared by real path on both sides.
     *
     * @param dir an absolute directory, as the argument owner resolved {@code --dir}
     * @throws UsageException naming the {@code project add} line for an unregistered directory, or
     *     the registered clone {@code dir} is inside
     */
    public RegisteredClone resolve(Path dir) {
        Path real = real(dir);
        for (RegisteredClone clone : clones()) {
            if (real(clone.clonePath()).equals(real)) {
                return clone;
            }
        }
        for (RegisteredClone clone : clones()) {
            if (real.startsWith(real(clone.clonePath()))) {
                throw new UsageException(dir + " is inside the registered clone " + clone.clonePath() + " of project "
                        + clone.project() + "; run with --dir=" + clone.clonePath());
            }
        }
        throw new UsageException(dir + " is not a registered clone; register it with: gnomish project add "
                + suggestedName(dir) + " --dir=" + dir);
    }

    /**
     * Registers {@code path} as a clone of project {@code name}, creating the project's file on
     * first use. The file is replaced atomically, so a refused or failed registration leaves it as
     * it was (NFR-R1).
     *
     * @param path an absolute, normalized directory
     * @return the new clone
     * @throws UsageException if {@code path} is not a git working tree, is already registered to
     *     any project, its clone name is taken in {@code name}, or the project file's clones map
     *     cannot be extended in place
     */
    public RegisteredClone add(ProjectName name, Path path) {
        CloneName clone = cloneName(path);
        if (!Files.isDirectory(path) || !Files.exists(path.resolve(".git"))) {
            throw new UsageException(path + " is not a git working tree");
        }
        Path real = real(path);
        for (RegisteredClone owned : clones()) {
            if (real(owned.clonePath()).equals(real)) {
                throw new UsageException(path + " is already registered as clone " + owned.cloneName() + " of project "
                        + owned.project());
            }
            if (owned.project().equals(name) && owned.cloneName().equals(clone)) {
                throw new UsageException(
                        "project " + name + " already has a clone named " + clone + " (" + owned.clonePath() + ")");
            }
        }
        ProjectLayout layout = home.project(name);
        ProjectFolders.Read read = reads.get(name);
        write(layout.config(), ProjectFile.withClone(layout.config(), read == null ? "" : read.text(), clone, path));
        return new RegisteredClone(name, clone, path, layout);
    }

    private List<RegisteredClone> clones() {
        return projects.stream().flatMap(p -> p.clones().stream()).toList();
    }

    private static CloneName cloneName(Path path) {
        Path last = path.getFileName();
        if (last == null) {
            throw new UsageException(path + " has no folder name to name the clone by");
        }
        return new CloneName(last.toString());
    }

    private static void write(Path file, String text) {
        try {
            AtomicFileWriter.write(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The real path of an existing directory; a missing one is compared as given. */
    private static Path real(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir.toAbsolutePath().normalize();
        }
    }

    /** The directory's last segment, lowercased and reduced to {@link ProjectName#SHAPE}. */
    static String suggestedName(Path dir) {
        Path last = dir.getFileName();
        String name = last == null ? "" : last.toString().toLowerCase(Locale.ROOT);
        String shaped = name.replaceAll("[^a-z0-9._-]", "-").replaceFirst("^[^a-z0-9]+", "");
        return shaped.isEmpty() ? FALLBACK_NAME : shaped;
    }
}
