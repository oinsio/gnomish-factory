package com.github.oinsio.gnomish.app.project;

import com.github.oinsio.gnomish.app.UsageException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.env.PropertySource;

/**
 * The project folders under the factory home's {@code projects/}: each folder's name checked as a
 * project name, and its {@code project.yaml} read once and parsed (design D3, NFR-P1). A folder
 * with no project file holds no project; a home with no {@code projects/} folder yet has none.
 *
 * <p>Implements FR2, FR3, NFR-P1 of add-project-registry.
 */
final class ProjectFolders {

    private static final String PROJECT_FILE = "project.yaml";

    private ProjectFolders() {}

    /**
     * One project file as read: the project it registers, its text — kept for the edit a
     * registration makes — and its parse, which also carries the project's {@code factory:} block.
     */
    record Read(RegisteredProject project, String text, List<PropertySource<?>> document) {}

    /**
     * Reads every project file under {@code home}, ordered by folder name.
     *
     * @throws UsageException if a project folder's name or a project file is invalid
     */
    static List<Read> read(FactoryHome home, OperatorFile.Reader reader) {
        List<Read> reads = new ArrayList<>();
        List<Path> folders = Files.isDirectory(home.projects()) ? folders(home.projects()) : List.of();
        for (Path folder : folders) {
            if (Files.isRegularFile(folder.resolve(PROJECT_FILE))) {
                ProjectLayout layout = home.project(projectName(folder));
                String text = OperatorFile.read(reader, layout.config());
                List<PropertySource<?>> document = OperatorFile.parse(layout.config(), text);
                reads.add(new Read(project(layout, document), text, document));
            }
        }
        return reads;
    }

    private static RegisteredProject project(ProjectLayout layout, List<PropertySource<?>> document) {
        Map<CloneName, Path> clones = ProjectFile.clones(layout.config(), document);
        return new RegisteredProject(
                layout,
                clones.entrySet().stream()
                        .map(e -> new RegisteredClone(layout.name(), e.getKey(), e.getValue(), layout))
                        .toList());
    }

    /** Every entry of {@code projects/}; one that is not a folder holds no project file. */
    private static List<Path> folders(Path projects) {
        try (Stream<Path> entries = Files.list(projects)) {
            return entries.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ProjectName projectName(Path folder) {
        try {
            return new ProjectName(String.valueOf(folder.getFileName()));
        } catch (IllegalArgumentException e) {
            throw new UsageException(folder + ": " + e.getMessage());
        }
    }
}
