package com.github.oinsio.gnomish.adapter.law;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw.Content;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw.Entry;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw.Unreadable;
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Freezes the pipeline {@link PipelineLaw} once at invocation start (D14, FR19 of
 * add-sandbox-core): given the law-source root — the root the runtime resolves
 * control-file and criteria references against (the factory clone's working-tree root
 * in git modes, the workspace root in in-place mode), never the gnome's per-task
 * worktree — it reads the content of every stage's control file and every judge check's
 * acceptance-criteria file into an immutable in-memory snapshot.
 *
 * <p>Reads go through the same {@link PathSafety#resolveWithinRoot} traversal guard the
 * loader uses ({@code ReferencedFiles}), so a reference escaping the law-source root is
 * captured as an unreadable entry rather than reaching an outside file. Per-file
 * failures are captured as {@link Unreadable} entries, never thrown here: freezing the
 * whole law must not fail merely because one stage's file is missing — the failure
 * surfaces at that stage's or vote's point of use as an infrastructure failure
 * ({@link UnreadableLawFileException}), preserving the FR13 "no attempt burned"
 * mechanics of the pre-rework lazy read.
 *
 * <p>Content is read once and never re-read: editing a law file in the gnome's working
 * copy after this returns has no effect on the running task — the contract test pins
 * exactly this (task 2.5).
 *
 * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core.
 */
public final class PipelineLawReader {

    private PipelineLawReader() {}

    /**
     * Freezes the law of {@code definition} from {@code lawSourceRoot}.
     *
     * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core.
     *
     * @param lawSourceRoot the root to resolve and read law files against — the factory
     *     clone's working-tree root in git modes, the workspace root in-place; never the
     *     gnome's per-task worktree
     * @param definition the loaded pipeline whose stage instructions and judge criteria
     *     files are frozen
     * @return the immutable frozen law; never null
     */
    public static PipelineLaw freeze(Path lawSourceRoot, PipelineDefinition definition) {
        Map<String, Entry> byRef = new LinkedHashMap<>();
        for (StageDefinition stage : definition.stages()) {
            capture(byRef, lawSourceRoot, stage.instructionsRef());
            for (VerifyCheck check : stage.verify()) {
                if (check instanceof VerifyCheck.Judge judge) {
                    capture(byRef, lawSourceRoot, judge.criteriaFile());
                }
            }
        }
        return new PipelineLaw(byRef);
    }

    /** Reads one reference into the map, capturing content or the reason it is unreadable. */
    private static void capture(Map<String, Entry> byRef, Path root, String ref) {
        if (byRef.containsKey(ref)) {
            return;
        }
        byRef.put(ref, read(root, ref));
    }

    private static Entry read(Path root, String ref) {
        PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(root, ref);
        if (resolution instanceof PathSafety.Escapes(String escapedRef)) {
            return new Unreadable("path escapes the configuration root: " + escapedRef);
        }
        PathSafety.Within within = (PathSafety.Within) resolution;
        try {
            return new Content(Files.readString(within.path()));
        } catch (IOException e) {
            String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Unreadable(reason);
        }
    }
}
