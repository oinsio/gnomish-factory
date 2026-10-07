package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
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
     * Runs the destructive tail of a completion whose finish this pickup delivered: the cleanup
     * commit removing {@code .gnomish-task/} from the tip, and the workspace disposal behind it
     * (FR9, FR10 of harden-task-branch-contract). Idempotent — an already-cleaned tip is left alone.
     */
    void finishCleanup(Path cloneDir, B branch);

    /**
     * Resumes a tip whose outcome is {@code null} — never recorded (process died mid-visit), or
     * cleared by the {@link #approveCheckpoint approval} or the {@link #resumeFrom resumed write}
     * that preceded this call (FR4, FR7 of make-checkpoint-gate-durable): salvages the interrupted
     * round's leftovers — or discards them under the order's {@code discardWork} — and runs the
     * engine once from {@code finalState}. The whole order is passed although the pipeline it
     * carries is also bound into this mechanics (design D4 of introduce-take-order): both are the
     * same startup definition on the resume path.
     */
    TakeResult resumeWithoutDecision(TakeOrder order, B branch, TaskState finalState);

    /**
     * Records the park a gate is owed when the tip holds the gate but not its park — the kill
     * window between the round commit and the park commit (FR4, FR11, design D8 of
     * make-checkpoint-gate-durable): the {@code Paused} outcome commit carrying the pending marker
     * (the park's durable intent), then the medium's delivery fence. No environment is
     * materialized and no stage runs.
     *
     * <p>Implements FR4, FR11 of make-checkpoint-gate-durable.
     *
     * @param paused the park the gate is owed; never null
     * @return the delivery fence's verdict on the recorded park; never null
     */
    ParkDeliveryVerdict recordPark(TakeOrder order, B branch, TaskOutcome.Paused paused);

    /**
     * Opens the gate the branch tip is held at — the <em>approval</em>, the pivot write a returned
     * checkpoint is continued through (FR3, design D2, D6 of make-checkpoint-gate-durable). Reads
     * the tip's state, derives the position past the gate through {@link
     * TaskState#approveGate} against the order's pinned definition, and hands both the gate read
     * off the tip and that state to {@code TaskRepository#approveCheckpoint} — one commit,
     * landed before any environment is materialized (in container mode the kept box is disposed
     * first, as for {@link #appendDecision}).
     *
     * <p>Implements FR3, FR4 of make-checkpoint-gate-durable.
     *
     * @return the approved state the continuation runs from; never null, never at a gate
     * @throws IllegalStateException when the tip is not at a gate the definition declares; nothing
     *     was written
     * @throws com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException when the
     *     repository refuses on the tip; nothing was written
     */
    TaskState approveCheckpoint(TakeOrder order, B branch);

    /**
     * Consumes the tip's recorded outcome without a decision — the <em>resumed write</em> (FR7,
     * design D4, D6 of make-checkpoint-gate-durable): {@code TaskRepository#resumeFrom} with
     * {@code reset}, one commit, landed before any environment is materialized (in container mode
     * the kept box is disposed first, as for {@link #appendDecision}).
     *
     * <p>Implements FR7 of make-checkpoint-gate-durable.
     *
     * @param reset the state the continued task resumes into; never null
     * @throws com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException when the tip records
     *     no outcome; nothing was written
     */
    void resumeFrom(TakeOrder order, B branch, TaskState reset);

    /**
     * Appends {@code decisionText} to the branch as a human decision, in one commit with the
     * attempt-counter reset it implies (FR4) — the durable intent the tracker acknowledge must never
     * precede (FR12 of harden-task-branch-contract). In container mode the kept box is disposed
     * first, so no factory-side commit lands behind a surviving box's back (FR17, design D12 of
     * the same change).
     *
     * @return the task context including the appended decision; never null
     */
    TaskContext appendDecision(
            Path cloneDir, B branch, TaskState finalState, TaskState resetState, String decisionText);

    /**
     * Resumes an {@code ESCALATION} park from a context and state that already reflect the human's
     * answer — or from the branch's own context when the return itself was the answer: runs the
     * engine once, appending nothing.
     */
    TakeResult resumeDecided(TakeOrder order, B branch, TaskContext context, TaskState resetState);
}
