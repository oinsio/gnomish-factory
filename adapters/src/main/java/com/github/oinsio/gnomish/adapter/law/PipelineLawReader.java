package com.github.oinsio.gnomish.adapter.law;

import com.github.oinsio.gnomish.adapter.law.PipelineLaw.Content;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw.Entry;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Freezes the pipeline {@link PipelineLaw} once at invocation start (D14, FR19 of
 * add-sandbox-core): given the {@link LawSource} the invocation's law is bound to, it
 * reads the content of every stage's control file and every judge check's
 * acceptance-criteria file into an immutable in-memory snapshot.
 *
 * <p>Which tree that source reads is not decided here (design D12 of
 * add-base-ref-resolution): a path that resolved a ref binds {@link GitObjectsLawSource}
 * at the law commit, while the in-place mode and manual {@code run} without
 * {@code --base} bind {@link WorkingTreeLawSource} over a directory — never the gnome's
 * per-task worktree either way. Both realizations answer the same three questions, so
 * this reader is medium-blind.
 *
 * <p>Per-file failures are captured as {@link PipelineLaw.Unreadable} entries, never
 * thrown here — the law source reports an unreadable file as data for exactly this
 * reason: freezing the whole law must not fail merely because one stage's file is
 * missing or escapes the law root. The failure surfaces at that stage's or vote's point
 * of use as an infrastructure failure ({@link UnreadableLawFileException}), preserving
 * the FR13 "no attempt burned" mechanics of the pre-rework lazy read.
 *
 * <p>Content is read once and never re-read: editing a law file in the gnome's working
 * copy after this returns has no effect on the running task — the contract test pins
 * exactly this (task 2.5).
 *
 * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core; FR11 of add-base-ref-resolution.
 */
public final class PipelineLawReader {

    private PipelineLawReader() {}

    /**
     * Freezes the law of {@code definition} from {@code lawSource}.
     *
     * <p>Implements FR19, NFR-S2, D14 of add-sandbox-core; FR11 of add-base-ref-resolution.
     *
     * @param lawSource the source this invocation's law is bound to
     * @param definition the loaded pipeline whose stage instructions and judge criteria
     *     files are frozen
     * @return the immutable frozen law; never null
     */
    public static PipelineLaw freeze(LawSource lawSource, PipelineDefinition definition) {
        Map<String, Entry> byRef = new LinkedHashMap<>();
        for (StageDefinition stage : definition.stages()) {
            capture(byRef, lawSource, stage.instructionsRef());
            for (VerifyCheck check : stage.verify()) {
                if (check instanceof VerifyCheck.Judge judge) {
                    capture(byRef, lawSource, judge.criteriaFile());
                }
            }
        }
        return new PipelineLaw(byRef);
    }

    /**
     * Freezes the law of {@code definition} from a working-tree law root — the boundary form for
     * a caller that already holds the {@code .gnomish/} directory itself. Production paths do not:
     * they state a {@code LawBinding} and let it resolve the law root for their medium (D12).
     *
     * <p>Implements FR19, D14 of add-sandbox-core; FR11 of add-base-ref-resolution.
     *
     * @param lawRoot the {@code .gnomish/} directory to resolve and read law files against; never
     *     a repository root, and never the gnome's per-task worktree
     * @param definition the loaded pipeline whose law is frozen
     * @return the immutable frozen law; never null
     */
    public static PipelineLaw freeze(Path lawRoot, PipelineDefinition definition) {
        return freeze(new WorkingTreeLawSource(lawRoot), definition);
    }

    /** Reads one reference into the map, capturing content or the reason it is unreadable. */
    private static void capture(Map<String, Entry> byRef, LawSource lawSource, String ref) {
        if (byRef.containsKey(ref)) {
            return;
        }
        byRef.put(ref, entry(lawSource.read(ref)));
    }

    /** The law source's read outcome as a frozen entry — content or captured reason, one to one. */
    private static Entry entry(LawSource.Read read) {
        return switch (read) {
            case LawSource.Text(String text) -> new Content(text);
            case LawSource.Unreadable(String reason) -> new PipelineLaw.Unreadable(reason);
        };
    }
}
