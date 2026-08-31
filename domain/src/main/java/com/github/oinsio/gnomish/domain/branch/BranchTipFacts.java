package com.github.oinsio.gnomish.domain.branch;

import org.jspecify.annotations.Nullable;

/**
 * Everything the classifier is allowed to know about one task branch tip: the status of each
 * envelope, the content facts read out of them, whether cleanup happened somewhere in history, and
 * the epochs to compare. Assembled by the adapter that owns the wire format, over the tip-reader
 * seam — never from a dirty worktree (FR5).
 *
 * <p>A value object, so classification is a pure function of it and the property-based spec can
 * generate every combination (M2).
 *
 * <p>Implements FR1, FR5 of harden-task-branch-contract.
 *
 * @param taskEnvelope what the reader found at {@code .gnomish-task/task.json}
 * @param stateEnvelope what the reader found at {@code .gnomish-task/state.json}
 * @param recordedOutcome the terminal outcome {@code task.json} records; {@code NONE} whenever the
 *     task envelope is not readable
 * @param roundsRecorded whether {@code state.json} records at least one round of the current stage
 * @param decisionsRecorded whether {@code task.json} records at least one human decision
 * @param cleanupCommitInHistory whether the cleanup commit appears anywhere in the branch's
 *     history — searched rather than assumed at {@code tip^}, so commits made after cleanup do not
 *     hide it
 * @param tipEpoch the claim epoch stamped on the tip, or {@code null} on a tip written before
 *     epochs were stamped
 * @param liveEpoch the epoch of the claim currently held, or {@code null} when the reader holds no
 *     claim (status and usage read without one)
 */
public record BranchTipFacts(
        EnvelopeStatus taskEnvelope,
        EnvelopeStatus stateEnvelope,
        RecordedTerminal recordedOutcome,
        boolean roundsRecorded,
        boolean decisionsRecorded,
        boolean cleanupCommitInHistory,
        @Nullable ClaimEpoch tipEpoch,
        @Nullable ClaimEpoch liveEpoch) {}
