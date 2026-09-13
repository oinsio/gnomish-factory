package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.law.LawEntry;
import com.github.oinsio.gnomish.adapter.law.LawSource;
import com.github.oinsio.gnomish.adapter.law.WorkingTreeLawSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-only discovery of a {@code .gnomish/} tree (task 6.1, FR1/FR8):
 * given the tree as a {@link LawSource}, it reads the raw text of the two
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
 * Nothing under the root is ever created, modified, or deleted — the law source
 * offers no way to.
 *
 * <p>Path-traversal rejection of file references is task 6.4, and
 * {@code pipeline.yaml} &harr; directory consistency is task 6.2 — neither is done
 * here.
 *
 * <p>Implements FR1, FR8 of load-pipeline-config; FR11 of add-base-ref-resolution.
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
     * Reads the {@code .gnomish/} tree the {@code law} source holds into a {@link RawConfig}.
     *
     * <p>Which tree that is — the working tree of the factory clone, or the law commit's own
     * tree read straight out of git objects — is the source's business, not this class's
     * (design D12 of add-base-ref-resolution): discovery asks it for two required files, one
     * directory listing, and each stage's manifest, and nothing else.
     *
     * <p>Implements FR1, FR8 of load-pipeline-config; FR11 of add-base-ref-resolution.
     *
     * @param law the law source rooted at the {@code .gnomish/} tree
     * @return the raw text of the required files and the discovered stages, sorted
     * @throws IOException when a required file ({@code config.yaml}, {@code pipeline.yaml})
     *     cannot be read, or the {@code stages/} directory cannot be enumerated
     */
    public static RawConfig read(LawSource law) throws IOException {
        String configText = readRequired(law, CONFIG);
        String pipelineText = readRequired(law, PIPELINE);
        return new RawConfig(configText, pipelineText, discoverStages(law));
    }

    /**
     * The boundary form for a caller that holds a directory: the same read over the working
     * tree rooted at {@code root}.
     *
     * <p>Implements FR1, FR8 of load-pipeline-config; FR11 of add-base-ref-resolution.
     *
     * @param root the {@code .gnomish/} directory root
     * @return the raw text of the required files and the discovered stages, sorted
     * @throws IOException when a required file cannot be read
     */
    public static RawConfig read(Path root) throws IOException {
        return read(new WorkingTreeLawSource(root));
    }

    /** Reads a required top-level file; its absence or unreadability is an I/O fault (FR8). */
    private static String readRequired(LawSource law, String name) throws IOException {
        return switch (law.read(name)) {
            case LawSource.Text(String text) -> text;
            case LawSource.Unreadable(String reason) ->
                throw new IOException("required file '" + name + "' cannot be read: " + reason);
        };
    }

    /** Lists {@code stages/} directories in sorted order, reading each manifest where present. */
    private static List<RawStage> discoverStages(LawSource law) throws IOException {
        List<RawStage> stages = new ArrayList<>();
        for (Path dir : sortByName(stageDirs(law))) {
            String name = dir.getFileName().toString();
            stages.add(new RawStage(name, readManifest(law, STAGES + "/" + name + "/" + MANIFEST)));
        }
        return stages;
    }

    /**
     * The subdirectories of {@code stages/}, as single-segment paths. A listing entry's name is
     * one path segment by construction, so the ordering contract below reads it exactly as it
     * read a filesystem path's file name. Anything that is not a directory — a stray
     * {@code README.md}, a symlink a git tree refuses to follow — is no stage.
     */
    private static List<Path> stageDirs(LawSource law) throws IOException {
        return law.list(STAGES).stream()
                .filter(entry -> entry.kind() == LawEntry.Kind.DIRECTORY)
                .map(entry -> Path.of(entry.name()))
                .toList();
    }

    /**
     * Orders stage directories by their file name (NFR-R1). Split from the listing and left
     * package-private so the ordering contract is verifiable with a deliberately-unsorted
     * input: a law source's own enumeration order is unspecified — the filesystem's is
     * platform-dependent, git's is the tree's — so no source-backed test can reliably
     * exercise the sort itself.
     */
    static List<Path> sortByName(List<Path> dirs) {
        return dirs.stream()
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
    }

    /**
     * Reads a stage manifest, or returns {@code null} when the directory has none (task 6.2).
     * A manifest the source cannot hand back is indistinguishable from an absent one here —
     * both leave task 6.2 to report the missing manifest as a located error, which is the
     * report the operator needs either way.
     */
    private static @Nullable String readManifest(LawSource law, String ref) {
        return law.read(ref) instanceof LawSource.Text(String text) ? text : null;
    }
}
