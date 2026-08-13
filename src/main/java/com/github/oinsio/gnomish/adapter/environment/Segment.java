package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.List;

/**
 * A contiguous run of pipeline stages that share one execution environment
 * (design D8, FR12): the unit an environment lives for. Every stage in a segment
 * resolves to the same {@link AdapterBinding}, and the environment is
 * materialized once at the segment's start and reused across its stages — no
 * repeated clone or container creation within a segment (NFR-P1). Crossing from
 * one segment to the next is executed as harvest → dispose → materialize (the
 * same mechanics as resume); computing where those boundaries fall is {@code
 * SegmentPlanner}'s job, applying them is the engine's.
 *
 * <p>A segment boundary is opened by a binding change or a stage's {@code
 * requires-fresh} declaration (FR13) — the latter forcing a fresh environment
 * even mid-binding — so the first stage of a segment is either the pipeline's
 * first stage, the first under a new binding, or a {@code requires-fresh} stage.
 *
 * <p>The record is inert, immutable data: the stage list is defensively copied
 * and unmodifiable, and always holds at least one stage (an empty environment
 * span is meaningless).
 *
 * <p>Implements FR12, FR13, NFR-P1 of add-sandbox-core.
 *
 * @param binding the adapter binding shared by every stage in the segment
 * @param stages the segment's stages in pipeline order; never empty; immutable
 */
public record Segment(AdapterBinding binding, List<StageDefinition> stages) {

    public Segment {
        stages = List.copyOf(stages);
        requireNonEmpty(stages);
    }

    /**
     * Fails fast on an empty stage list: a segment is an environment's lifespan,
     * and an environment with no stage to run has no reason to exist. Kept as an
     * explicit static method rather than inline in the compact constructor because
     * PIT's record filter suppresses mutations inside a record's canonical
     * constructor, which would exempt this from the mutation gate.
     */
    private static void requireNonEmpty(List<StageDefinition> stages) {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("Segment.stages must not be empty");
        }
    }
}
