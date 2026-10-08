package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.sandbox.LiveBox;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.SegmentPlanner;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The run's one live round environment, leased per stage over the {@link
 * SegmentPlanner}'s plan (FR12, FR13, NFR-P1): within a segment the same
 * materialized environment is reused with no new clone or container; crossing a
 * segment boundary executes harvest → dispose → materialize — the same
 * mechanics as resume, nothing new (D8). Materialization is lazy: the first
 * {@link #environmentFor} call materializes (and, via {@link
 * SelfCheckedEnvironment}, self-checks) the first environment, so factory-side
 * work that must precede the box — the resume decision commit (D19), law
 * binding — naturally lands first.
 *
 * <p>The live box itself — one box per segment index, its lock and its build — is {@link
 * LiveBox}'s (D13 of make-checkpoint-gate-durable); this class owns the key (stage → segment
 * index over the plan) and the harvest, and holds no lock of its own. The harvest of the previous
 * box at a boundary is this class's step, taken with nothing held before the live box is asked
 * for the new segment, so the live box's phase 2 then disposes it. The lease is driven by one
 * slot thread; other threads only read ({@link #currentIfLeased()}), and a read never waits for
 * a build in flight.
 *
 * <p>Implements FR12, FR13, NFR-P1 of add-sandbox-core; FR21 of make-checkpoint-gate-durable.
 */
public final class EnvironmentLease {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLease.class);

    private final Map<String, Integer> stageToSegment;
    private final LiveBox<Integer> box;

    /**
     * @param factory creates a fresh, unmaterialized round environment per segment; never null
     * @param branch the task branch environments materialize on; never blank
     * @param segments the run's segment plan in pipeline order; never empty
     */
    public EnvironmentLease(
            Supplier<? extends TaskExecutionEnvironment> factory, String branch, List<Segment> segments) {
        this.stageToSegment = index(segments);
        this.box = new LiveBox<>(factory, (fresh, segment) -> fresh.materialize(branch, null));
    }

    /**
     * The environment stage {@code stageName} runs in: the current one within a
     * segment, a freshly materialized one across a boundary (harvesting and
     * disposing the previous first).
     *
     * @param stageName the stage about to run; must belong to the planned pipeline
     * @return the materialized, self-checked environment; never null
     */
    public TaskExecutionEnvironment environmentFor(String stageName) {
        Integer segment = stageToSegment.get(stageName);
        if (segment == null) {
            throw new IllegalArgumentException("stage \"" + stageName + "\" is not part of the planned pipeline");
        }
        Optional<TaskExecutionEnvironment> previous = box.retiredBy(segment);
        if (previous.isPresent()) {
            log.info("segment boundary before stage {}: harvest, dispose, materialize (FR12)", stageName);
            previous.get().harvest();
        }
        return box.environmentFor(segment);
    }

    /**
     * The currently leased environment, for collaborators that act between
     * rounds of the stage in flight (persistence, same-box checks, salvage).
     *
     * @throws IllegalStateException if no stage has leased an environment yet
     */
    public TaskExecutionEnvironment current() {
        return box.current()
                .orElseThrow(() -> new IllegalStateException("no environment leased yet: no stage has run"));
    }

    /**
     * The currently leased environment, if any — for end-of-run bookkeeping that must not force
     * one. Never waits: empty while a build is in flight.
     */
    public Optional<TaskExecutionEnvironment> currentIfLeased() {
        return box.current();
    }

    /** Disposes the leased environment, if any; idempotent (Completed cleanup). */
    public void dispose() {
        box.dispose();
    }

    private static Map<String, Integer> index(List<Segment> segments) {
        Map<String, Integer> byStage = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            for (StageDefinition stage : segments.get(i).stages()) {
                byStage.put(stage.name(), i);
            }
        }
        return Map.copyOf(byStage);
    }
}
