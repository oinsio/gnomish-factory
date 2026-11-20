package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * One {@code persistPending} call as both persistence ends read it (design D21 of
 * migrate-verify-to-executor-contract): the stage from the state's position — the one source of the
 * stage, since a pending entry carries none — and the list's last entry, whose round and token every
 * entry of the open round shares: the check whose first {@code pending} answer the write lands, or,
 * for a key rotation (D12 as amended 2026-11-12), the last of the list the rotated entry is replaced
 * in. Both ends take the subject and the round's key from here, so neither medium can render them
 * differently.
 *
 * <p>Implements FR2, FR20 of migrate-verify-to-executor-contract.
 *
 * @param taskId the task whose round is open
 * @param stage the stage the open round belongs to
 * @param last the last entry of the open round's list
 */
record PendingWrite(String taskId, String stage, PendingPollRecord last) {

    /**
     * Reads the call against the port's contract: the position names a stage and the list is not
     * empty.
     *
     * @throws GitPersistFailedException if the state breaks the contract — nothing is written
     */
    static PendingWrite of(String taskId, TaskState state) {
        if (!(state.position() instanceof Position.AtStage(String stage))) {
            throw refused(taskId, "?", -1, "the state is not positioned at a stage: " + state.position());
        }
        if (state.pendingPolls().isEmpty()) {
            throw refused(taskId, stage, -1, "the state carries no pending entry");
        }
        return new PendingWrite(taskId, stage, state.pendingPolls().getLast());
    }

    /** The open round's key: the stage, and the attempt the last entry names. */
    AttemptKey key() {
        return new AttemptKey(taskId, stage, last.attempt());
    }

    /** The pending commit's subject, {@code gnomish: pending <stage>#<round> <token>}. */
    String subject() {
        return ServiceCommitMessages.pending(stage, last);
    }

    /** The refusal of a round with nothing registered to land the commit on, before any write. */
    GitPersistFailedException noSnapshot() {
        return refused(
                taskId,
                stage,
                last.attempt(),
                "the round has no registered snapshot to land a pending commit on (its snapshot was declined)");
    }

    private static GitPersistFailedException refused(String taskId, String stage, int round, String why) {
        return new GitPersistFailedException(taskId, stage, round, "pending commit", UntrustedText.factory(why));
    }
}
