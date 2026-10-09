package com.github.oinsio.gnomish.domain.branch;

import com.github.oinsio.gnomish.domain.engine.Position;
import java.util.Optional;

/**
 * Everything the classifier is allowed to know about one task branch tip: the status of each
 * envelope, the content facts read out of them, and whether cleanup happened somewhere in history.
 * No claim epoch is among them — the stamp on the tip is provenance, not a classification input
 * (fix-claim-epoch-fence FR1). Assembled by the adapter that owns the wire format, over the
 * tip-reader seam — never from a dirty worktree (FR5).
 *
 * <p>A value object, so classification is a pure function of it and the property-based spec can
 * generate every combination (M2).
 *
 * <p>The recorded position is a fact because a gate is a shape of its own: a tip whose {@code
 * state.json} holds the task at {@link Position.AwaitingApproval} classifies by that position,
 * whatever {@code task.json}'s outcome says (design D8 of make-checkpoint-gate-durable).
 *
 * <p>Implements FR1, FR5 of harden-task-branch-contract; FR1 of fix-claim-epoch-fence; FR11 of
 * make-checkpoint-gate-durable.
 *
 * @param taskEnvelope what the reader found at {@code .gnomish-task/task.json}
 * @param stateEnvelope what the reader found at {@code .gnomish-task/state.json}
 * @param recordedOutcome the terminal outcome {@code task.json} records; {@code NONE} whenever the
 *     task envelope is not readable
 * @param recordedPosition the position {@code state.json} records; empty whenever the state
 *     envelope is absent or not readable
 * @param roundsRecorded whether {@code state.json} records at least one round of the current stage
 * @param decisionsRecorded whether {@code task.json} records at least one human decision
 * @param cleanupCommitInHistory whether the cleanup commit appears anywhere in the branch's
 *     history — searched rather than assumed at {@code tip^}, so commits made after cleanup do not
 *     hide it
 */
public record BranchTipFacts(
        EnvelopeStatus taskEnvelope,
        EnvelopeStatus stateEnvelope,
        RecordedTerminal recordedOutcome,
        Optional<Position> recordedPosition,
        boolean roundsRecorded,
        boolean decisionsRecorded,
        boolean cleanupCommitInHistory) {}
