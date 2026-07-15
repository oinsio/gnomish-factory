package com.github.oinsio.gnomish.adapter.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Read-only filesystem discovery for a {@code .gnomish/} tree (task 6.1,
 * FR1/FR8): given the tree's root {@link Path}, it reads the raw text of the two
 * required top-level files ({@code config.yaml}, {@code pipeline.yaml}),
 * discovers the {@code stages/<name>/} directories deterministically, and reads
 * each {@code stages/<name>/stage.yaml} where present. The result is a
 * {@link RawConfig} of raw text the parser ({@link StructuralParse}) consumes
 * next — this class never parses YAML.
 *
 * <p><b>I/O fault vs validation problem (design D3, FR8).</b> The two top-level
 * files are <em>required to exist</em>: an absent or unreadable {@code config.yaml}
 * or {@code pipeline.yaml} is a genuine I/O fault and surfaces as an
 * {@link IOException}, never a {@code ConfigError}. Validation problems are data;
 * I/O faults are exceptions. What is deferred to <em>semantic</em> validation
 * (task 6.2), and so is NOT an I/O fault here:
 *
 * <ul>
 *   <li>a {@code stages/<name>/} directory that lacks a {@code stage.yaml} — read
 *       as a {@link RawStage} with {@code null} text, so task 6.2 can report the
 *       missing manifest;</li>
 *   <li>an absent {@code stages/} directory — an empty stage list, so task 6.2 can
 *       reconcile it against {@code pipeline.yaml} (a pipeline stage with no
 *       directory is a dangling reference, its concern not this one's).</li>
 * </ul>
 *
 * <p><b>Determinism &amp; read-only (NFR-R1).</b> Stage directories are sorted by
 * name so repeated reads of the same tree are equal; {@code pipeline.yaml} remains
 * the order source of truth downstream (FR3), but discovery itself is stable.
 * Nothing under the root is ever created, modified, or deleted.
 *
 * <p>Path-traversal rejection of file references is task 6.4, and
 * {@code pipeline.yaml} &harr; directory consistency is task 6.2 — neither is done
 * here.
 *
 * <p>Implements FR1, FR8 of load-pipeline-config.
 */
public final class GnomishFiles {

    private static final String CONFIG = "config.yaml";
    private static final String PIPELINE = "pipeline.yaml";
    private static final String STAGES = "stages";
    private static final String MANIFEST = "stage.yaml";

    private GnomishFiles() {}

    /**
     * The raw, unparsed contents of a {@code .gnomish/} tree.
     *
     * @param configText the verbatim text of {@code config.yaml}
     * @param pipelineText the verbatim text of {@code pipeline.yaml}
     * @param stages the discovered stages in deterministic (sorted-by-name) order
     */
    public record RawConfig(String configText, String pipelineText, List<RawStage> stages) {

        public RawConfig {
            stages = List.copyOf(stages);
        }
    }

    /**
     * One discovered stage directory: its name (from the directory name, the input
     * to {@code pipeline.yaml} order per FR3) paired with the raw {@code stage.yaml}
     * text, or {@code null} when the directory has no manifest (task 6.2 reports the
     * missing manifest).
     *
     * @param name the stage directory name
     * @param text the raw {@code stage.yaml} text, or {@code null} when absent
     */
    public record RawStage(String name, @Nullable String text) {}

    /**
     * Reads a {@code .gnomish/} tree rooted at {@code root} into a {@link RawConfig}.
     *
     * <p>Implements FR1, FR8 of load-pipeline-config.
     *
     * @param root the {@code .gnomish/} directory root
     * @return the raw text of the required files and the discovered stages, sorted
     * @throws IOException when a required file ({@code config.yaml},
     *     {@code pipeline.yaml}) or a discovered {@code stage.yaml} cannot be read
     */
    public static RawConfig read(Path root) throws IOException {
        String configText = readRequired(root.resolve(CONFIG), CONFIG);
        String pipelineText = readRequired(root.resolve(PIPELINE), PIPELINE);
        List<RawStage> stages = discoverStages(root.resolve(STAGES));
        return new RawConfig(configText, pipelineText, stages);
    }

    /** Reads a required top-level file; its absence or unreadability is an I/O fault (FR8). */
    private static String readRequired(Path file, String name) throws IOException {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IOException("required file '" + name + "' cannot be read", e);
        }
    }

    /** Lists {@code stages/} directories in sorted order, reading each manifest where present. */
    private static List<RawStage> discoverStages(Path stagesDir) throws IOException {
        if (!Files.isDirectory(stagesDir)) {
            return List.of();
        }
        List<Path> dirs = sortedStageDirs(stagesDir);
        List<RawStage> stages = new ArrayList<>();
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            stages.add(new RawStage(name, readManifest(dir.resolve(MANIFEST))));
        }
        return stages;
    }

    /** Deterministic (NFR-R1): sub-directories of {@code stages/} sorted by name. */
    private static List<Path> sortedStageDirs(Path stagesDir) throws IOException {
        try (Stream<Path> entries = Files.list(stagesDir)) {
            return sortByName(entries.filter(Files::isDirectory).toList());
        }
    }

    /**
     * Orders stage directories by their file name (NFR-R1). Split from the
     * {@link Files#list} enumeration and left package-private so the ordering
     * contract is verifiable with a deliberately-unsorted input: a filesystem's
     * own enumeration order is unspecified and platform-dependent, so no
     * filesystem-backed test can reliably exercise the sort itself.
     */
    static List<Path> sortByName(List<Path> dirs) {
        return dirs.stream()
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
    }

    /** Reads a stage manifest, or returns {@code null} when the directory has none (task 6.2). */
    private static @Nullable String readManifest(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        return Files.readString(manifest);
    }
}
