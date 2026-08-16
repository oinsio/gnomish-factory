package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.SegmentPlanner;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
 * <p>Implements FR12, FR13, NFR-P1 of add-sandbox-core.
 */
public final class EnvironmentLease {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLease.class);

    private final Supplier<? extends TaskExecutionEnvironment> factory;
    private final String branch;
    private final Map<String, Integer> stageToSegment;

    private @Nullable TaskExecutionEnvironment current;
    private int currentSegment = -1;

    /**
     * @param factory creates a fresh, unmaterialized round environment per segment; never null
     * @param branch the task branch environments materialize on; never blank
     * @param segments the run's segment plan in pipeline order; never empty
     */
    public EnvironmentLease(
            Supplier<? extends TaskExecutionEnvironment> factory, String branch, List<Segment> segments) {
        this.factory = factory;
        this.branch = branch;
        this.stageToSegment = index(segments);
    }

    /**
     * The environment stage {@code stageName} runs in: the current one within a
     * segment, a freshly materialized one across a boundary (harvesting and
     * disposing the previous first).
     *
     * @param stageName the stage about to run; must belong to the planned pipeline
     * @return the materialized, self-checked environment; never null
     */
    public synchronized TaskExecutionEnvironment environmentFor(String stageName) {
        Integer segment = stageToSegment.get(stageName);
        if (segment == null) {
            throw new IllegalArgumentException("stage \"" + stageName + "\" is not part of the planned pipeline");
        }
        TaskExecutionEnvironment env = current;
        if (env != null && segment == currentSegment) {
            return env;
        }
        if (env != null) {
            log.info("segment boundary before stage {}: harvest, dispose, materialize (FR12)", stageName);
            env.harvest();
            env.dispose();
        }
        TaskExecutionEnvironment fresh = factory.get();
        fresh.materialize(branch, null);
        current = fresh;
        currentSegment = segment;
        return fresh;
    }

    /**
     * The currently leased environment, for collaborators that act between
     * rounds of the stage in flight (persistence, same-box checks, salvage).
     *
     * @throws IllegalStateException if no stage has leased an environment yet
     */
    public synchronized TaskExecutionEnvironment current() {
        TaskExecutionEnvironment env = current;
        if (env == null) {
            throw new IllegalStateException("no environment leased yet: no stage has run");
        }
        return env;
    }

    /** The currently leased environment, if any — for end-of-run bookkeeping that must not force one. */
    public synchronized Optional<TaskExecutionEnvironment> currentIfLeased() {
        return Optional.ofNullable(current);
    }

    /** Disposes the leased environment, if any; idempotent (Completed cleanup). */
    public synchronized void dispose() {
        TaskExecutionEnvironment env = current;
        if (env != null) {
            env.dispose();
            current = null;
            currentSegment = -1;
        }
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
