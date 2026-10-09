package com.github.oinsio.gnomish.app.port;

import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.ObjectId;

/**
 * The port through which the runner durably records a task's lifecycle events —
 * distinct from the engine's round-scoped {@code AttemptPersistence} (design D1):
 * creating the task at start, appending a human {@link Decision} on resume, opening a checkpoint
 * gate ({@link #approveCheckpoint}), consuming a recorded outcome without a decision
 * ({@link #resumeFrom}), and
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
     * commit the task originates from. Branch creation, worktree setup, and any
     * other origination machinery are adapter concerns (design D1, out of scope for
     * this port); this method only guarantees that the task's origin is durably
     * recorded so it can later be audited and used by resume/divergence checks
     * (FR7, D7).
     *
     * <p><b>The start point is a commit, never a name</b> (FR15, design D12 of
     * add-base-ref-resolution, revised 2026-09-10). The caller has already peeled its
     * law binding exactly once — {@code lawCommit} is that peel — so an implementer
     * resolves nothing: it verifies the object is a commit this repository holds and
     * starts the branch there. The parameter type is the enforcement. Passing a name
     * instead let git's bare-name lookup order (gitrevisions: {@code $GIT_DIR/<n>},
     * {@code refs/<n>}, {@code refs/tags/<n>}, {@code refs/heads/<n>}, {@code
     * refs/remotes/<n>}) answer with a stale local branch or a planted local tag,
     * while the law was read from the refreshed remote-tracking ref — law and branch
     * naming two different commits.
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
     * @param lawCommit the commit this task starts from — the peeled law commit, so the
     *     branch, the frozen law and the recorded {@code baseCommit} are one SHA by
     *     construction; never null
     * @param pin the durable base pin recorded beside the commit — the ref name, its
     *     namespace and the tier that produced it, so resume never re-resolves the base
     *     (FR7 of add-base-ref-resolution); {@link BasePin#UNPINNED} where no resolution
     *     named a ref; never null
     * @param initialState the task's starting state — positioned at the pipeline's
     *     first stage, no attempts burned, empty totals; never null
     */
    void createTask(TaskContext context, ObjectId lawCommit, BasePin pin, TaskState initialState);

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
     * Durably opens the gate of a {@code manual} stage that passed — the <em>approval</em>, the one
     * writer of a position past a gate (FR3, design D2 of make-checkpoint-gate-durable). In one
     * commit: {@code state.json} becomes {@code approved}, {@code task.json}'s {@code outcome} is
     * cleared and its tracker-write-pending marker set to false, {@code lastEscalation} and the
     * decisions are kept. The position past the gate and the cleared park are only true together,
     * so no tip shows one without the other.
     *
     * <p>The repository holds no pipeline definition and computes nothing: the caller holding the
     * pinned definition derived {@code approved} through {@code TaskState.approveGate}, the one
     * owner of "what follows a stage". The implementation refuses — writing nothing, on what the
     * branch tip alone shows — unless the tip's position equals {@code gate} and {@code approved}'s
     * position is not itself a gate. Naming the gate is what makes a stale approval harmless: an
     * approval computed from one gate can never open another the tip has since reached, and a
     * repeated approval finds a tip it already moved and refuses (NFR-R2).
     *
     * <p>Implements FR3, NFR-O1, NFR-R2 of make-checkpoint-gate-durable.
     *
     * @param taskId the task whose gate is opened; never blank
     * @param gate the gate the caller read off the tip and asks to open; never null
     * @param approved the state past the gate — {@code TaskState.approveGate} of the gated state,
     *     attempt history untouched; never null and never itself at a gate
     * @throws CheckpointApprovalRefusedException when the tip is not at {@code gate}, or {@code
     *     approved} is at a gate; nothing was written
     */
    void approveCheckpoint(String taskId, Position.AwaitingApproval gate, TaskState approved);

    /**
     * Durably consumes a recorded outcome without a decision — the <em>resumed write</em> (FR7,
     * design D4 of make-checkpoint-gate-durable): the continuation that picks a parked task back up
     * with no human reply (a return without a reply, an infrastructure-class return, {@code run
     * --resume} without {@code --decision}). In one commit: {@code state.json} becomes {@code
     * reset}, {@code task.json}'s {@code outcome} is cleared and its tracker-write-pending marker
     * set to false, {@code lastEscalation} and the decisions are kept. "Attempts reset" and
     * "outcome consumed" are only true together, so no tip shows one without the other.
     *
     * <p>The implementation refuses — writing nothing, on what the branch tip alone shows — when
     * the tip's {@code outcome} is already null: there is nothing to consume, and a repeated
     * resumed write finds the tip it already moved (NFR-R2), so the attempt budget a return grants
     * is granted once.
     *
     * <p>Implements FR7, FR8, NFR-O1, NFR-R2 of make-checkpoint-gate-durable.
     *
     * @param taskId the task whose recorded outcome is consumed; never blank
     * @param reset the state the continued task resumes into — {@link TaskState#resetAttempts()} of
     *     the state the park was produced from; never null
     * @throws ResumedWriteRefusedException when the tip records no outcome; nothing was written
     */
    void resumeFrom(String taskId, TaskState reset);

    /**
     * Durably records the terminal {@link TaskOutcome} for the task identified by
     * {@code taskId}: {@code Completed}, {@code Paused}, {@code Escalated}, or
     * {@code Aborted}. This is the write that lets an external reader distinguish a
     * task parked with a known outcome from one whose process merely died
     * mid-flight, where outcome stays null (FR5, NFR-R2).
     *
     * <p>The record also states whether a tracker write follows it ({@link TrackerWrite}): with
     * {@link TrackerWrite#OWED} a park or a completion carries the durable "tracker-write pending"
     * marker its receipt later clears; with {@link TrackerWrite#NONE} no marker is set, because no
     * write is coming and no receipt will follow (design D8 of make-run-headless).
     *
     * <p>Idempotent on identical content: a record whose {@code task.json} equals the tip's byte for
     * byte makes no commit and returns normally, since the tip already carries it (design D8 of
     * make-run-headless, "Idempotence"; crash-consistency item 8).
     *
     * <p>Implements FR1 of add-git-workflow; FR10 of make-run-headless.
     *
     * @param taskId the task the outcome belongs to; never blank
     * @param outcome the terminal outcome to record; never null
     * @param trackerWrite whether a tracker write follows this record; never null
     */
    void recordOutcome(String taskId, TaskOutcome outcome, TrackerWrite trackerWrite);
}
