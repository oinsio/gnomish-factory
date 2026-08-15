package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;

/**
 * Turns a {@link TaskSnapshot} — the tracker task's id/title/body, fetched exactly once at
 * first claim — into the initial {@link TaskContext}/{@link TaskState} pair the engine starts
 * from. The tracker-task analogue of {@link com.github.oinsio.gnomish.app.AdHocTaskSynthesizer}
 * for ad-hoc runs: same output shape, but the identity/description comes from a pre-fetched
 * snapshot rather than being generated or parsed from CLI input, and the starting stage is
 * always the pipeline's first stage — a tracker task never supports {@code --from-stage}
 * (design D4).
 *
 * <p><b>Called only at first claim.</b> Resume never calls this class and never re-fetches the
 * tracker: {@link com.github.oinsio.gnomish.app.ResumeBootstrap#context()} is read from the
 * task branch's persisted {@code task.json} (populated, at first claim, from the very {@link
 * TaskContext} this class produces), and {@code GitResumeContinuation} threads
 * that same {@code context} through every resume path (recorded position, escalated,
 * paused) without ever touching a tracker port. This satisfies FR11's "resume SHALL NOT re-read
 * the snapshot" — later issue edits on the tracker cannot affect a running or parked task,
 * because nothing downstream of first claim reads the tracker for id/title/body again.
 *
 * <p>Implements FR11 of add-tracker-port.
 */
public final class TrackerTaskSynthesizer {

    private TrackerTaskSynthesizer() {}

    /**
     * Synthesizes a tracker task's initial context and state from {@code snapshot} and the
     * loaded {@code definition}.
     *
     * <p>Implements FR11 of add-tracker-port.
     *
     * @param snapshot the task's id/title/body frozen at first claim; carried verbatim into the
     *     resulting {@link TaskContext} with an empty decision list — a freshly claimed task has
     *     collected no human decisions yet
     * @param definition the pipeline definition whose first stage supplies the initial position;
     *     a tracker task always starts at pipeline start (design D4)
     * @return the synthesized task context and its initial state, positioned at the pipeline's
     *     first stage
     */
    public static SynthesizedTrackerTask synthesize(TaskSnapshot snapshot, PipelineDefinition definition) {
        TaskContext context = new TaskContext(snapshot.id(), snapshot.title(), snapshot.body(), List.of());
        String startStage = definition.stages().getFirst().name();
        TaskState initialState = TaskState.atStageStart(startStage);
        return new SynthesizedTrackerTask(context, initialState);
    }

    /**
     * The synthesized tracker task, ready for the engine's first run: {@code context} carries
     * the snapshot's id/title/body with no decisions yet, {@code initialState} positions it at
     * the pipeline's first stage with no attempts burned.
     *
     * @param context the synthesized task identity and description, frozen from the snapshot
     * @param initialState the initial engine state, positioned at the pipeline's first stage
     */
    public record SynthesizedTrackerTask(TaskContext context, TaskState initialState) {}
}
