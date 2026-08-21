package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Everything a resumed {@code take} does differently in host and container mode, behind one seam,
 * so that {@link TakeDispositionResume}'s routing table can be written once (design D8 of
 * add-serve-sandbox-lifecycle). Host mode materializes a worktree and salvages leftovers in it;
 * container mode reattaches a box (or recreates one over the surviving volume) and salvages in-box
 * — the routing decision above is identical either way, and the two implementations exist so it
 * stays that way by construction rather than by two javadocs promising to mirror each other.
 *
 * <p>An implementation is bound to ONE resume: it carries the pipeline (and, in container mode, the
 * segment plan) the run advances through, which is why those are absent from every method below.
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port.
 *
 * @param <B> the loaded-branch bundle this mechanics produces and consumes
 */
public interface ResumeMechanics<B extends ResumedBranch> {

    /**
     * Locates the task branch for {@code taskId} and loads its {@code task.json}.
     *
     * <p>Returns {@code null} — and only ever for this one reason — when the branch tip carries no
     * {@code .gnomish-task/} at all: the shape a {@code Completed} cleanup commit leaves behind
     * (FR15 of add-git-workflow), meaning the work was delivered while the tracker finish never
     * landed. How that absence surfaces is mechanism-specific (a missing file in a materialized
     * worktree, a missing blob in bare objects), which is exactly why the translation into this one
     * shared answer belongs here and not in the routing table (design D8).
     *
     * @throws UsageException if no branch for {@code taskId} exists anywhere
     */
    @Nullable
    B loadBranch(Path cloneDir, String taskId);

    /** The last durably recorded {@code state.json} of {@code branch}. */
    TaskState readFinalState(B branch);

    /**
     * Clears the branch's durable "tracker-write pending" marker once a deferred park's tracker
     * write has confirmed (FR10, D10 of add-claim-heartbeat).
     */
    void confirmTerminalWrite(Path cloneDir, B branch);

    /**
     * Resumes a {@code null} (process died mid-visit), {@code CHECKPOINT}, or {@code INFRA} park:
     * salvages the interrupted round's leftovers — or discards them under {@code discardWork} — and
     * runs the engine once from {@code finalState}.
     */
    TakeResult resumeWithoutDecision(
            Path cloneDir,
            B branch,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId);

    /**
     * Resumes an {@code ESCALATION} park: resets the attempt counter, appends {@code decisionText}
     * as a decision when one is present, and runs the engine once.
     */
    TakeResult resumeWithDecision(
            Path cloneDir,
            B branch,
            TaskState finalState,
            @Nullable String decisionText,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId);
}
