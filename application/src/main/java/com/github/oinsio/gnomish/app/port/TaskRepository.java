package com.github.oinsio.gnomish.app.port;

import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;

/**
 * The port through which the runner durably records a task's lifecycle events —
 * distinct from the engine's round-scoped {@code AttemptPersistence} (design D1):
 * creating the task at start, appending a human {@link Decision} on resume, and
 * recording the final {@link TaskOutcome} at completion or parking. Where
 * {@code AttemptPersistence} is driven by the engine once per round,
 * {@code TaskRepository} is driven by the runner once per lifecycle event, and
 * both seams are implemented by the same adapter over the same task branch
 * (FR1, FR2).
 *
 * <p>Like {@code AttemptPersistence}, this is a strict port: an implementation
 * that cannot durably record a lifecycle event signals it by throwing rather
 * than by a return value, so the caller can treat a broken durability guarantee
 * as fatal instead of silently continuing on unrecorded state.
 *
 * <p>Implements FR1 of add-git-workflow; FR3 of harden-task-branch-contract.
 */
public interface TaskRepository {

    /**
     * Durably records the start of a new task: its {@link TaskContext} — identity,
     * title, body, and any decisions already known at start — together with the
     * reference the task originates from. The reference is deliberately opaque to
     * this port: git branch creation, worktree setup, and any other origination
     * machinery are adapter concerns (design D1, out of scope for this port); this
     * method only guarantees that the task's origin is durably recorded so it can
     * later be audited and used by resume/divergence checks (FR7, D7).
     *
     * <p>Implements FR1 of add-git-workflow.
     *
     * <p>The record includes the task's {@code initialState} (FR3, design D2 of
     * harden-task-branch-contract): the starting position the caller synthesized from
     * the frozen pipeline law, recorded in the <em>same</em> durable write as the
     * context. Without it, a run that dies before its first round completes leaves a
     * branch whose state is unreadable, and the resume that follows cannot tell an
     * unstarted task from a delivered one — the crash loop FR3 closes. Implementers
     * therefore SHALL NOT record the context alone and synthesize state later.
     *
     * @param context the new task's identity and description; never null
     * @param baseRef the reference this task started from — the current state of
     *     the caller's working copy unless explicitly overridden; never blank
     * @param initialState the task's starting state — positioned at the pipeline's
     *     first stage, no attempts burned, empty totals; never null
     */
    void createTask(TaskContext context, String baseRef, TaskState initialState);

    /**
     * Durably appends a {@link Decision} for the task identified by {@code taskId} —
     * the human input that unblocks a resumed run after an escalation (FR8).
     *
     * <p>Contract note for implementers (design D9, FR5): appending the resume
     * decision is understood to also reset the task's {@code outcome} to null, in
     * the same durable write, marking the start of a new visit. Without this reset
     * a task parked by a prior outcome and a task freshly resumed but not yet
     * finished would be indistinguishable to an external reader. This method does
     * not expose the reset as a separate parameter — an adapter honoring the
     * contract performs it as part of appending the decision.
     *
     * <p>Implements FR1 of add-git-workflow.
     *
     * <p>The same rule covers the attempt counter (FR4, design of harden-task-branch-contract):
     * a decision and the attempt-counter reset it implies are true only together, so they land in
     * one durable write. Recording the decision first and resetting the counter at the next round
     * commit leaves a kill window whose frozen state reads "answered, but still exhausted" — a
     * resume that re-escalates immediately.
     *
     * @param taskId the task the decision belongs to; never blank
     * @param decision the human decision to append; never null
     * @param resetState the state the answered task resumes into — {@link
     *     TaskState#resetAttempts()} of the state the park was produced from; never null
     */
    void appendDecision(String taskId, Decision decision, TaskState resetState);

    /**
     * Durably records the terminal {@link TaskOutcome} for the task identified by
     * {@code taskId}: {@code Completed}, {@code Paused}, {@code Escalated}, or
     * {@code Aborted}. This is the write that lets an external reader distinguish a
     * task parked with a known outcome from one whose process merely died
     * mid-flight, where outcome stays null (FR5, NFR-R2).
     *
     * <p>Implements FR1 of add-git-workflow.
     *
     * @param taskId the task the outcome belongs to; never blank
     * @param outcome the terminal outcome to record; never null
     */
    void recordOutcome(String taskId, TaskOutcome outcome);
}
