package com.github.oinsio.gnomish.app.port;

import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

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
 * <p>Implements FR1 of add-git-workflow.
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
     * @param context the new task's identity and description; never null
     * @param baseRef the reference this task started from — the current state of
     *     the caller's working copy unless explicitly overridden; never blank
     */
    void createTask(TaskContext context, String baseRef);

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
     * @param taskId the task the decision belongs to; never blank
     * @param decision the human decision to append; never null
     */
    void appendDecision(String taskId, Decision decision);

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
