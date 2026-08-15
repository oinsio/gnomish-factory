package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Groups a pipeline's stages into environment {@link Segment}s (design D8, FR12,
 * FR13): the plan of where an environment is materialized, reused, and disposed
 * across a run. Walking the stages in pipeline order, it starts a new segment
 * whenever the resolved {@link AdapterBinding} changes or a stage declares {@code
 * requires-fresh}, and otherwise extends the current segment so consecutive
 * same-binding stages share one environment (NFR-P1).
 *
 * <p>Planning is pure over the {@link PipelineDefinition} and the {@link
 * BindingResolver} — it materializes nothing and consults no live adapter; the
 * engine executes the harvest → dispose → materialize boundaries the plan
 * implies.
 *
 * <p>Implements FR12, FR13, NFR-P1 of add-sandbox-core.
 *
 * @param bindingResolver resolves each stage to the binding it runs under;
 *     never null
 */
public record SegmentPlanner(BindingResolver bindingResolver) {

    /**
     * Computes the ordered segments of {@code pipeline}: contiguous stages sharing
     * a binding form one segment, a binding change or a {@code requires-fresh}
     * stage opens the next. The segments cover every stage exactly once, in
     * pipeline order.
     *
     * @param pipeline the pipeline whose stages to segment; never null
     * @return the segments in pipeline order; empty only for a pipeline with no
     *     stages
     */
    public List<Segment> plan(PipelineDefinition pipeline) {
        List<Segment> segments = new ArrayList<>();
        List<StageDefinition> current = new ArrayList<>();
        AdapterBinding currentBinding = null;
        for (StageDefinition stage : pipeline.stages()) {
            AdapterBinding binding = bindingResolver.resolve(stage.name());
            if (opensNewSegment(currentBinding, binding, stage)) {
                flush(segments, currentBinding, current);
                current = new ArrayList<>();
                currentBinding = binding;
            }
            current.add(stage);
        }
        flush(segments, currentBinding, current);
        return List.copyOf(segments);
    }

    /**
     * A new segment opens on a binding change or on a {@code requires-fresh} stage
     * (which forces a fresh environment even mid-binding, FR13). The pipeline's
     * first stage is covered by the binding-change clause alone: {@code
     * currentBinding} starts null, so the first non-null binding always differs —
     * no separate first-stage guard is needed.
     */
    private static boolean opensNewSegment(
            @Nullable AdapterBinding currentBinding, AdapterBinding binding, StageDefinition stage) {
        return currentBinding != binding || stage.executor().sandbox().requiresFresh();
    }

    /**
     * Emits the accumulated segment, unless there is none to emit — which happens
     * exactly once, before the first stage, when {@code binding} is still null.
     * A non-null binding always accompanies a non-empty stage list here (every
     * stage is added to {@code current} before the next flush), so the null guard
     * alone is a faithful "nothing accumulated yet" test.
     */
    private static void flush(List<Segment> segments, @Nullable AdapterBinding binding, List<StageDefinition> stages) {
        if (binding != null) {
            segments.add(new Segment(binding, stages));
        }
    }
}
